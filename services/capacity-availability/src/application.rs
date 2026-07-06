// ---------------------------------------------------------------------------
// Application layer: wires domain operations with EventPublisher port.
// ---------------------------------------------------------------------------

use crate::domain::*;
use crate::ports::{EventPublisher, HandlerResult, PublishFailed, WireEnvelope};
use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

/// Application service for capacity-availability operations.
pub struct CapacityService {
    pools: Mutex<HashMap<String, InventoryPool>>,
    publisher: Arc<dyn EventPublisher>,
    producer: String,
    /// Idempotency cache: idempotency_key -> request fingerprint and cached JSON response for replay.
    idempotency_cache: Mutex<HashMap<String, IdempotencyRecord>>,
}

impl CapacityService {
    pub fn new(publisher: Arc<dyn EventPublisher>) -> Self {
        Self {
            pools: Mutex::new(HashMap::new()),
            publisher,
            producer: "capacity-availability".to_string(),
            idempotency_cache: Mutex::new(HashMap::new()),
        }
    }

    /// Handle an inbound event from subscribed streams. The operation is idempotent at
    /// the subscriber adapter by eventId; unknown events are safely ignored.
    pub fn handle_inbound_event(&self, envelope: WireEnvelope) -> HandlerResult {
        match envelope.event_type.as_str() {
            "SegmentReservationRequested" => self.handle_segment_reservation_requested(&envelope),
            "SegmentReservationConfirmed" => self.handle_segment_reservation_confirmed(&envelope),
            "SegmentBookingCancelled" | "PostSalesApplied" => {
                self.handle_release_trigger(&envelope)
            }
            _ => HandlerResult::Success,
        }
    }

    fn handle_segment_reservation_requested(&self, envelope: &WireEnvelope) -> HandlerResult {
        let Some(segment_booking_id) = string_field(&envelope.payload, "segmentBookingId") else {
            return HandlerResult::Success;
        };
        let Some(segment_ref) = string_field(&envelope.payload, "segmentRef") else {
            return HandlerResult::FatalError("missing segmentRef".into());
        };
        let Some(traveler_ref) = string_field(&envelope.payload, "travelerRef") else {
            return HandlerResult::FatalError("missing travelerRef".into());
        };
        let idempotency_key = string_field(&envelope.payload, "idempotencyKey")
            .unwrap_or_else(|| format!("{}:{}:hold", envelope.event_id, segment_booking_id));
        let request = HoldCapacityRequest {
            segment_ref,
            traveler_ref,
            class_ref: string_field(&envelope.payload, "classRef")
                .or_else(|| string_field(&envelope.payload, "seatClassRef"))
                .unwrap_or_else(|| "standard".to_string()),
            quantity: quantity_field(&envelope.payload).unwrap_or(1),
            segment_booking_id,
        };

        match self.hold_capacity(request, &idempotency_key, &envelope.correlation_id) {
            Ok(_) => HandlerResult::Success,
            Err(AppError::Unavailable(message)) | Err(AppError::Internal(message)) => {
                HandlerResult::TransientError(message)
            }
            Err(error) => HandlerResult::FatalError(error.message().to_string()),
        }
    }

    fn handle_segment_reservation_confirmed(&self, envelope: &WireEnvelope) -> HandlerResult {
        let Some(hold_id) = string_field(&envelope.payload, "capacityHoldId") else {
            return HandlerResult::Success;
        };
        let idempotency_key = format!("{}:{}:confirm", envelope.event_id, hold_id);
        match self.confirm_hold(&hold_id, &idempotency_key, &envelope.correlation_id) {
            Ok(_) | Err(AppError::PreconditionFailed(_)) | Err(AppError::NotFound(_)) => {
                HandlerResult::Success
            }
            Err(AppError::Unavailable(message)) | Err(AppError::Internal(message)) => {
                HandlerResult::TransientError(message)
            }
            Err(error) => HandlerResult::FatalError(error.message().to_string()),
        }
    }

    fn handle_release_trigger(&self, envelope: &WireEnvelope) -> HandlerResult {
        let Some(hold_id) = string_field(&envelope.payload, "capacityHoldId")
            .or_else(|| string_field(&envelope.payload, "holdId"))
        else {
            return HandlerResult::Success;
        };
        let idempotency_key = format!("{}:{}:release", envelope.event_id, hold_id);
        match self.release_hold(&hold_id, &idempotency_key, &envelope.correlation_id) {
            Ok(_) | Err(AppError::NotFound(_)) => HandlerResult::Success,
            Err(AppError::Unavailable(message)) | Err(AppError::Internal(message)) => {
                HandlerResult::TransientError(message)
            }
            Err(error) => HandlerResult::FatalError(error.message().to_string()),
        }
    }

    /// Query availability snapshot for a service segment.
    pub fn query_availability(
        &self,
        scheduled_service_ref: &str,
        segment_ref: &str,
    ) -> Result<AvailabilitySnapshotResponse, AppError> {
        if scheduled_service_ref.trim().is_empty() || segment_ref.trim().is_empty() {
            return Err(AppError::ValidationFailed(
                "scheduledServiceRef and segmentRef are required".into(),
            ));
        }

        let pools = self
            .pools
            .lock()
            .map_err(|e| AppError::Internal(format!("lock error: {}", e)))?;

        // Find matching pools by segment reference patterns
        let mut total_remaining = 0usize;
        let mut total_available = 0usize;
        let mut sellable = false;
        let mut status = "UNAVAILABLE".to_string();
        let mut remaining_by_class: Vec<RemainingByClass> = Vec::new();

        for pool in pools.values() {
            if pool.identity.scheduled_service_ref == scheduled_service_ref
                || pool.identity.service_segment_ref == segment_ref
                || pool.identity.route_segment_ref == segment_ref
            {
                let now = now_millis();
                // Create a dummy interval for snapshot
                if let Ok(interval) = StationInterval::new(0, 1) {
                    let snapshot = pool.availability_snapshot(
                        format!("avs-{}", uuid::Uuid::now_v7()),
                        interval,
                        now,
                        now + 30000,
                    );
                    total_remaining += snapshot.total_units;
                    total_available += snapshot.available_count;
                    if snapshot.sellable {
                        sellable = true;
                    }
                    // Use the most available status
                    match snapshot.status {
                        AvailabilityStatus::Available => status = "AVAILABLE".to_string(),
                        AvailabilityStatus::Limited => {
                            if status != "AVAILABLE" {
                                status = "LIMITED".to_string();
                            }
                        }
                        AvailabilityStatus::Unknown => {
                            if status != "AVAILABLE" && status != "LIMITED" {
                                status = "UNKNOWN".to_string();
                            }
                        }
                        AvailabilityStatus::Unavailable => { /* keep default */ }
                    }
                    remaining_by_class.push(RemainingByClass {
                        class_ref: pool.identity.seat_class_or_cabin_ref.clone(),
                        total: snapshot.total_units,
                        available: snapshot.available_count,
                    });
                }
            }
        }

        Ok(AvailabilitySnapshotResponse {
            snapshot_id: format!("avs-{}", uuid::Uuid::now_v7()),
            snapshot_version: 1,
            scheduled_service_ref: scheduled_service_ref.to_string(),
            segment_ref: segment_ref.to_string(),
            captured_at: unix_millis_to_rfc3339(now_millis()),
            valid_until: unix_millis_to_rfc3339(now_millis() + 30000),
            sellable,
            remaining_by_class,
            total_units: total_remaining,
            available_count: total_available,
            status,
        })
    }

    /// Hold capacity for a segment.
    pub fn hold_capacity(
        &self,
        req: HoldCapacityRequest,
        idempotency_key: &str,
        correlation_id: &str,
    ) -> Result<HoldCapacityResponse, AppError> {
        if req.segment_ref.trim().is_empty()
            || req.traveler_ref.trim().is_empty()
            || req.class_ref.trim().is_empty()
            || req.segment_booking_id.trim().is_empty()
        {
            return Err(AppError::ValidationFailed(
                "segmentRef, travelerRef, classRef, and segmentBookingId are required".into(),
            ));
        }
        if req.quantity == 0 {
            return Err(AppError::ValidationFailed(
                "quantity must be greater than zero".into(),
            ));
        }

        let fingerprint = req.fingerprint();
        if let Some(resp) =
            self.idempotency_replay::<HoldCapacityResponse>(idempotency_key, &fingerprint)?
        {
            return Ok(resp);
        }

        let now = now_millis();
        let hold_id = format!("hold-{}", uuid::Uuid::now_v7());

        let mut pools = self
            .pools
            .lock()
            .map_err(|e| AppError::Internal(format!("lock error: {}", e)))?;

        // Find or create a pool for this segment/class
        let pool_key = format!("{}:{}", req.segment_ref, req.class_ref);
        let pool = pools.entry(pool_key.clone()).or_insert_with(|| {
            // Create a minimal pool
            let identity = InventoryPoolIdentity::new(
                format!("pool-{}", uuid::Uuid::now_v7()),
                format!("scheduled-service:{}", req.segment_ref),
                "2026-07-05",
                &req.class_ref,
                "seat",
                &req.segment_ref,
                &req.segment_ref,
                "v1",
            )
            .unwrap();
            // Create some capacity units based on quantity
            let units: Vec<CapacityUnitRef> = (0..req.quantity.max(10))
                .map(|i| {
                    let seat = format!("{:02}{}", ((i / 4) + 1), ['A', 'B', 'C', 'D'][i % 4]);
                    CapacityUnitRef::new(seat).unwrap()
                })
                .collect();
            InventoryPool::new(identity, units).unwrap()
        });

        // Find a unit that is actually free for the requested interval.
        let interval =
            StationInterval::new(0, 1).map_err(|e| AppError::Internal(e.to_string()))?;
        let unit_ref = pool
            .find_available_unit(&interval, now)
            .ok_or_else(|| AppError::Unavailable("No capacity units available in pool".into()))?;

        // Create the hold
        let hold = CapacityHold::request(
            HoldId::new(&hold_id).map_err(|e| AppError::Internal(e.to_string()))?,
            HoldScope::new(
                pool.identity.pool_id.clone(),
                unit_ref,
                StationInterval::new(0, 1).map_err(|e| AppError::Internal(e.to_string()))?,
                ReferenceMetadata::new(
                    "booking-orchestration",
                    "purchase-hold",
                    Some(req.segment_booking_id.clone()),
                    Some(req.segment_booking_id.clone()),
                    Some(req.traveler_ref.clone()),
                )
                .map_err(|e| AppError::Internal(e.to_string()))?,
            ),
            IdempotencyKey::new(idempotency_key).map_err(|e| AppError::Internal(e.to_string()))?,
            now,
            now + 300000, // 5 min expiry
        )
        .map_err(|e| AppError::Internal(e.to_string()))?;

        match pool.request_hold(hold, now) {
            Ok(event) => {
                self.publish_domain_event(&event, correlation_id)
                    .map_err(AppError::from_publish_failed)?;
                let resp = HoldCapacityResponse {
                    hold_id,
                    segment_ref: req.segment_ref.clone(),
                    status: "HELD".to_string(),
                    held_until: unix_millis_to_rfc3339(now + 300000),
                };
                self.store_idempotency_response(idempotency_key, fingerprint, &resp)?;
                Ok(resp)
            }
            Err(DomainError::IdempotencyConflict { .. }) => Err(AppError::IdempotencyKeyReused(
                "Idempotency-Key was reused with a different request body".into(),
            )),
            Err(DomainError::UnknownCapacityUnit(_)) => Err(AppError::DomainRuleViolation(
                "Unknown capacity unit".into(),
            )),
            Err(e) => Err(AppError::DomainRuleViolation(e.to_string())),
        }
    }

    /// Confirm a hold.
    pub fn confirm_hold(
        &self,
        hold_id: &str,
        idempotency_key: &str,
        correlation_id: &str,
    ) -> Result<ConfirmHoldResponse, AppError> {
        let fingerprint = format!("confirm:{}", hold_id);
        if let Some(resp) =
            self.idempotency_replay::<ConfirmHoldResponse>(idempotency_key, &fingerprint)?
        {
            return Ok(resp);
        }

        let now = now_millis();
        let mut pools = self
            .pools
            .lock()
            .map_err(|e| AppError::Internal(format!("lock error: {}", e)))?;

        for pool in pools.values_mut() {
            if pool
                .hold(
                    &HoldId::new(hold_id)
                        .map_err(|_| AppError::NotFound("hold not found".into()))?,
                )
                .is_some()
            {
                match pool.confirm_hold(
                    &HoldId::new(hold_id)
                        .map_err(|_| AppError::NotFound("hold not found".into()))?,
                    now,
                ) {
                    Ok(event) => {
                        self.publish_domain_event(&event, correlation_id)
                            .map_err(AppError::from_publish_failed)?;
                        let resp = ConfirmHoldResponse {
                            hold_id: hold_id.to_string(),
                            status: "CONFIRMED".to_string(),
                        };
                        self.store_idempotency_response(idempotency_key, fingerprint, &resp)?;
                        return Ok(resp);
                    }
                    Err(DomainError::InvalidHoldState { .. }) => {
                        return Err(AppError::PreconditionFailed(
                            "Hold is not in a state that can be confirmed".into(),
                        ));
                    }
                    Err(e) => return Err(AppError::DomainRuleViolation(e.to_string())),
                }
            }
        }
        Err(AppError::NotFound("hold not found".into()))
    }

    /// Release a hold.
    pub fn release_hold(
        &self,
        hold_id: &str,
        idempotency_key: &str,
        correlation_id: &str,
    ) -> Result<ReleaseHoldResponse, AppError> {
        let fingerprint = format!("release:{}", hold_id);
        if let Some(resp) =
            self.idempotency_replay::<ReleaseHoldResponse>(idempotency_key, &fingerprint)?
        {
            return Ok(resp);
        }

        let now = now_millis();
        let mut pools = self
            .pools
            .lock()
            .map_err(|e| AppError::Internal(format!("lock error: {}", e)))?;

        for pool in pools.values_mut() {
            if pool
                .hold(
                    &HoldId::new(hold_id)
                        .map_err(|_| AppError::NotFound("hold not found".into()))?,
                )
                .is_some()
            {
                match pool.release_hold(
                    &HoldId::new(hold_id)
                        .map_err(|_| AppError::NotFound("hold not found".into()))?,
                    now,
                    "client-requested-release",
                ) {
                    Ok(event) => {
                        self.publish_domain_event(&event, correlation_id)
                            .map_err(AppError::from_publish_failed)?;
                        let resp = ReleaseHoldResponse {
                            hold_id: hold_id.to_string(),
                            status: "RELEASED".to_string(),
                        };
                        self.store_idempotency_response(idempotency_key, fingerprint, &resp)?;
                        return Ok(resp);
                    }
                    Err(e) => return Err(AppError::DomainRuleViolation(e.to_string())),
                }
            }
        }
        Err(AppError::NotFound("hold not found".into()))
    }

    fn idempotency_replay<T: DeserializeOwned>(
        &self,
        idempotency_key: &str,
        fingerprint: &str,
    ) -> Result<Option<T>, AppError> {
        let cache = self
            .idempotency_cache
            .lock()
            .map_err(|e| AppError::Internal(e.to_string()))?;
        let Some(record) = cache.get(idempotency_key) else {
            return Ok(None);
        };
        if record.fingerprint != fingerprint {
            return Err(AppError::IdempotencyKeyReused(
                "Idempotency-Key was reused with a different request body".into(),
            ));
        }
        serde_json::from_value(record.response.clone())
            .map(Some)
            .map_err(|_| AppError::Internal("Failed to deserialize cached response".into()))
    }

    fn store_idempotency_response<T: Serialize>(
        &self,
        idempotency_key: &str,
        fingerprint: String,
        response: &T,
    ) -> Result<(), AppError> {
        let record = IdempotencyRecord {
            fingerprint,
            response: serde_json::to_value(response).map_err(|e| {
                AppError::Internal(format!("Failed to serialize cached response: {}", e))
            })?,
        };
        self.idempotency_cache
            .lock()
            .map_err(|e| AppError::Internal(e.to_string()))?
            .insert(idempotency_key.to_string(), record);
        Ok(())
    }

    /// Get a hold by ID.
    pub fn get_hold(&self, hold_id: &str) -> Result<GetHoldResponse, AppError> {
        let pools = self
            .pools
            .lock()
            .map_err(|e| AppError::Internal(format!("lock error: {}", e)))?;

        for pool in pools.values() {
            if let Some(hold) = pool.hold(
                &HoldId::new(hold_id).map_err(|_| AppError::NotFound("hold not found".into()))?,
            ) {
                let status = match hold.state {
                    CapacityHoldState::Requested => "REQUESTED",
                    CapacityHoldState::Held => "HELD",
                    CapacityHoldState::Confirmed => "CONFIRMED",
                    CapacityHoldState::Released => "RELEASED",
                    CapacityHoldState::Expired => "EXPIRED",
                    CapacityHoldState::Failed => "FAILED",
                };
                return Ok(GetHoldResponse {
                    hold_id: hold.hold_id.to_string(),
                    segment_ref: hold
                        .scope
                        .references
                        .segment_booking_ref
                        .clone()
                        .unwrap_or_default(),
                    status: status.to_string(),
                    held_until: unix_millis_to_rfc3339(hold.expires_at),
                    requested_at: unix_millis_to_rfc3339(hold.requested_at),
                    traveler_ref: hold.scope.references.traveler_ref.clone(),
                    class_ref: hold.scope.capacity_unit_ref.to_string(),
                });
            }
        }
        Err(AppError::NotFound("hold not found".into()))
    }

    fn publish_domain_event(
        &self,
        event: &DomainEvent,
        correlation_id: &str,
    ) -> Result<(), PublishFailed> {
        let (event_type, payload) = match event {
            DomainEvent::CapacityHeld(e) => (
                "CapacityHeld",
                json!({
                    "holdId": e.hold_id.to_string(),
                    "inventoryPoolId": e.inventory_pool_id.to_string(),
                    "capacityUnitRef": e.capacity_unit_ref.to_string(),
                    "interval": { "fromSeq": e.interval.from_seq(), "toSeq": e.interval.to_seq() },
                    "idempotencyKey": e.idempotency_key.to_string(),
                    "expiresAt": unix_millis_to_rfc3339(e.expires_at),
                    "idempotentReplay": e.idempotent_replay,
                }),
            ),
            DomainEvent::CapacityHoldConfirmed(e) => (
                "CapacityHoldConfirmed",
                json!({
                    "holdId": e.hold_id.to_string(),
                    "inventoryPoolId": e.inventory_pool_id.to_string(),
                    "capacityUnitRef": e.capacity_unit_ref.to_string(),
                    "interval": { "fromSeq": e.interval.from_seq(), "toSeq": e.interval.to_seq() },
                    "confirmedAt": unix_millis_to_rfc3339(e.confirmed_at),
                }),
            ),
            DomainEvent::CapacityReleased(e) => (
                "CapacityReleased",
                json!({
                    "holdId": e.hold_id.to_string(),
                    "inventoryPoolId": e.inventory_pool_id.to_string(),
                    "capacityUnitRef": e.capacity_unit_ref.to_string(),
                    "interval": { "fromSeq": e.interval.from_seq(), "toSeq": e.interval.to_seq() },
                    "releasedAt": unix_millis_to_rfc3339(e.released_at),
                    "releaseReason": e.release_reason,
                    "references": {
                        "sourceContext": e.references.source_context,
                        "orderRef": e.references.order_ref,
                        "segmentBookingRef": e.references.segment_booking_ref,
                        "travelerRef": e.references.traveler_ref,
                    },
                }),
            ),
            DomainEvent::CapacityHoldExpired(e) => (
                "CapacityHoldExpired",
                json!({
                    "holdId": e.hold_id.to_string(),
                    "inventoryPoolId": e.inventory_pool_id.to_string(),
                    "capacityUnitRef": e.capacity_unit_ref.to_string(),
                    "interval": { "fromSeq": e.interval.from_seq(), "toSeq": e.interval.to_seq() },
                    "expiredAt": unix_millis_to_rfc3339(e.expired_at),
                }),
            ),
            DomainEvent::CapacityHoldFailed(e) => (
                "CapacityHoldFailed",
                json!({
                    "requestedHoldId": e.requested_hold_id.to_string(),
                    "inventoryPoolId": e.inventory_pool_id.to_string(),
                    "capacityUnitRef": e.capacity_unit_ref.to_string(),
                    "interval": { "fromSeq": e.interval.from_seq(), "toSeq": e.interval.to_seq() },
                    "reason": format!("{:?}", e.reason),
                }),
            ),
        };

        let envelope = WireEnvelope::try_new(
            event_type,
            unix_millis_to_rfc3339(now_millis()),
            rust_kit::messaging::valid_or_generated_correlation_id(correlation_id),
            None::<String>,
            self.producer.clone(),
            payload,
        )
        .map_err(|error| PublishFailed(error.to_string()))?;

        self.publisher.publish(&envelope)
    }
}

// ---------------------------------------------------------------------------
// Request / Response DTOs
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct AvailabilitySnapshotResponse {
    pub snapshot_id: String,
    pub snapshot_version: u64,
    pub scheduled_service_ref: String,
    pub segment_ref: String,
    pub captured_at: String,
    pub valid_until: String,
    pub sellable: bool,
    pub remaining_by_class: Vec<RemainingByClass>,
    pub total_units: usize,
    pub available_count: usize,
    pub status: String,
}

#[derive(Debug, Clone)]
pub struct RemainingByClass {
    pub class_ref: String,
    pub total: usize,
    pub available: usize,
}

#[derive(Debug, Clone)]
pub struct HoldCapacityRequest {
    pub segment_ref: String,
    pub traveler_ref: String,
    pub class_ref: String,
    pub quantity: usize,
    pub segment_booking_id: String,
}

impl HoldCapacityRequest {
    fn fingerprint(&self) -> String {
        format!(
            "hold:{}:{}:{}:{}:{}",
            self.segment_ref,
            self.traveler_ref,
            self.class_ref,
            self.quantity,
            self.segment_booking_id
        )
    }
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct HoldCapacityResponse {
    pub hold_id: String,
    pub segment_ref: String,
    pub status: String,
    pub held_until: String,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct ConfirmHoldResponse {
    pub hold_id: String,
    pub status: String,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct ReleaseHoldResponse {
    pub hold_id: String,
    pub status: String,
}

#[derive(Debug, Clone)]
pub struct GetHoldResponse {
    pub hold_id: String,
    pub segment_ref: String,
    pub status: String,
    pub held_until: String,
    pub requested_at: String,
    pub traveler_ref: Option<String>,
    pub class_ref: String,
}

// ---------------------------------------------------------------------------
// Application error types
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
struct IdempotencyRecord {
    fingerprint: String,
    response: serde_json::Value,
}

#[derive(Debug, Clone)]
pub enum AppError {
    ValidationFailed(String),
    NotFound(String),
    Conflict(String),
    IdempotencyKeyReused(String),
    PreconditionFailed(String),
    DomainRuleViolation(String),
    Unavailable(String),
    Internal(String),
}

impl AppError {
    fn from_publish_failed(error: PublishFailed) -> Self {
        AppError::Unavailable(error.to_string())
    }

    pub fn status_code(&self) -> u16 {
        match self {
            AppError::ValidationFailed(_) => 400,
            AppError::NotFound(_) => 404,
            AppError::Conflict(_) => 409,
            AppError::IdempotencyKeyReused(_) => 422,
            AppError::PreconditionFailed(_) => 412,
            AppError::DomainRuleViolation(_) => 422,
            AppError::Unavailable(_) => 503,
            AppError::Internal(_) => 500,
        }
    }

    pub fn code(&self) -> &str {
        match self {
            AppError::ValidationFailed(_) => "VALIDATION_FAILED",
            AppError::NotFound(_) => "NOT_FOUND",
            AppError::Conflict(_) => "CONFLICT",
            AppError::IdempotencyKeyReused(_) => "IDEMPOTENCY_KEY_REUSED",
            AppError::PreconditionFailed(_) => "PRECONDITION_FAILED",
            AppError::DomainRuleViolation(_) => "DOMAIN_RULE_VIOLATION",
            AppError::Unavailable(_) => "UNAVAILABLE",
            AppError::Internal(_) => "INTERNAL_ERROR",
        }
    }

    pub fn message(&self) -> &str {
        match self {
            AppError::ValidationFailed(m) => m,
            AppError::NotFound(m) => m,
            AppError::Conflict(m) => m,
            AppError::IdempotencyKeyReused(m) => m,
            AppError::PreconditionFailed(m) => m,
            AppError::DomainRuleViolation(m) => m,
            AppError::Unavailable(m) => m,
            AppError::Internal(m) => m,
        }
    }
}

fn quantity_field(value: &Value) -> Option<usize> {
    value
        .get("quantity")
        .and_then(Value::as_u64)
        .and_then(|quantity| usize::try_from(quantity).ok())
        .filter(|quantity| *quantity > 0)
}

fn string_field(value: &Value, field: &str) -> Option<String> {
    value
        .get(field)
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned)
}

fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
