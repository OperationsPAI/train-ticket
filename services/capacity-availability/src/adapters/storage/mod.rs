use crate::application::{
    AppError, AvailabilitySnapshotResponse, ConfirmHoldResponse, HoldCapacityRequest,
    HoldCapacityResponse, ReleaseHoldResponse,
};
use crate::domain::*;
use crate::ports::WireEnvelope;
use rust_kit::messaging::{HandlerResult, stream_for_producer};
use rust_kit::storage::{
    DbIdempotencyStore, IdempotencyTxDecision, OutboxAppender, PgTransaction, Snapshot,
    SnapshotRepository, Storage, StorageError, mark_event_processing,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sqlx::PgPool;

const INVENTORY_TABLE: &str = "inventory_pool_snapshots";
const HOLD_TABLE: &str = "capacity_hold_snapshots";
const SNAPSHOT_TABLE: &str = "availability_snapshot_snapshots";
const PRODUCER: &str = "capacity-availability";
const MAX_RETRIES: usize = 3;

#[derive(Clone)]
pub struct PostgresCapacityService {
    storage: Storage,
    inventory_repo: SnapshotRepository,
    hold_repo: SnapshotRepository,
    snapshot_repo: SnapshotRepository,
}

impl PostgresCapacityService {
    pub async fn from_env() -> Result<Self, StorageError> {
        let storage = Storage::from_env().await?;
        let migrations_dir =
            std::env::var("MIGRATIONS_DIR").unwrap_or_else(|_| "/app/migrations".into());
        match storage.migrate_dir(&migrations_dir).await {
            Ok(()) => {}
            Err(primary_error) => {
                if migrations_dir == "/app/migrations" {
                    storage
                        .migrate_dir("services/capacity-availability/migrations")
                        .await?;
                } else {
                    return Err(primary_error);
                }
            }
        }
        Self::from_storage(storage)
    }

    pub fn new(pool: PgPool) -> Result<Self, StorageError> {
        let storage = Storage::new(pool);
        storage.mark_migrations_ready();
        Self::from_storage(storage)
    }

    pub fn from_storage(storage: Storage) -> Result<Self, StorageError> {
        Ok(Self {
            storage,
            inventory_repo: SnapshotRepository::new(INVENTORY_TABLE)?,
            hold_repo: SnapshotRepository::new(HOLD_TABLE)?,
            snapshot_repo: SnapshotRepository::new(SNAPSHOT_TABLE)?,
        })
    }

    pub fn pool(&self) -> &PgPool {
        self.storage.pool()
    }

    pub async fn is_ready(&self) -> bool {
        self.storage.is_ready().await
    }

    pub async fn query_availability(
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
            .load_pools_for_availability_query(scheduled_service_ref, segment_ref)
            .await?;
        let now = now_millis();
        let mut total_units = 0;
        let mut available_count = 0;
        let mut sellable = false;
        let mut status = "UNAVAILABLE".to_string();
        let mut remaining_by_class = Vec::new();
        for pool in pools {
            let snapshot = pool.availability_snapshot(
                format!("avs-{}", uuid::Uuid::now_v7()),
                StationInterval::new(0, 1).map_err(to_internal)?,
                now,
                now + 30_000,
            );
            total_units += snapshot.total_units;
            available_count += snapshot.available_count;
            sellable |= snapshot.sellable;
            match snapshot.status {
                AvailabilityStatus::Available => status = "AVAILABLE".to_string(),
                AvailabilityStatus::Limited if status != "AVAILABLE" => {
                    status = "LIMITED".to_string()
                }
                AvailabilityStatus::Unknown if status != "AVAILABLE" && status != "LIMITED" => {
                    status = "UNKNOWN".to_string()
                }
                _ => {}
            }
            remaining_by_class.push(crate::application::RemainingByClass {
                class_ref: pool.identity.seat_class_or_cabin_ref.clone(),
                total: snapshot.total_units,
                available: snapshot.available_count,
            });
        }
        let response = AvailabilitySnapshotResponse {
            snapshot_id: format!("avs-{}", uuid::Uuid::now_v7()),
            snapshot_version: 1,
            scheduled_service_ref: scheduled_service_ref.to_string(),
            segment_ref: segment_ref.to_string(),
            captured_at: unix_millis_to_rfc3339(now),
            valid_until: unix_millis_to_rfc3339(now + 30_000),
            sellable,
            remaining_by_class,
            total_units,
            available_count,
            status,
        };
        let mut tx = self.pool().begin().await.map_err(to_app_storage)?;
        self.snapshot_repo
            .save(&mut tx, &response.snapshot_id, None, &response)
            .await
            .map_err(to_app_storage)?;
        tx.commit().await.map_err(to_app_storage)?;
        Ok(response)
    }

    pub async fn hold_capacity(
        &self,
        req: HoldCapacityRequest,
        idempotency_key: &str,
        correlation_id: &str,
    ) -> Result<HoldCapacityResponse, AppError> {
        validate_hold_request(&req)?;
        let fingerprint = req.fingerprint();
        if let Some(resp) = self
            .idempotency_replay::<HoldCapacityResponse>(idempotency_key, &fingerprint)
            .await?
        {
            return Ok(resp);
        }
        for attempt in 0..MAX_RETRIES {
            match self
                .try_hold_capacity(&req, idempotency_key, &fingerprint, correlation_id)
                .await
            {
                Err(AppError::Conflict(_)) if attempt + 1 < MAX_RETRIES => continue,
                result => return result,
            }
        }
        Err(AppError::Conflict("capacity hold write conflict".into()))
    }

    async fn try_hold_capacity(
        &self,
        req: &HoldCapacityRequest,
        idempotency_key: &str,
        fingerprint: &str,
        correlation_id: &str,
    ) -> Result<HoldCapacityResponse, AppError> {
        let now = now_millis();
        let hold_id = format!("hold-{}", uuid::Uuid::now_v7());
        let pool_id = pool_id_for(&req.segment_ref, &req.class_ref);
        let mut tx = self.pool().begin().await.map_err(to_app_storage)?;
        if let Some(resp) = self
            .claim_idempotency::<HoldCapacityResponse>(&mut tx, idempotency_key, fingerprint)
            .await?
        {
            tx.commit().await.map_err(to_app_storage)?;
            return Ok(resp);
        }
        let loaded = self
            .inventory_repo
            .get::<InventoryPoolSnapshot>(&mut *tx, &pool_id)
            .await
            .map_err(to_app_storage)?;
        let (mut pool, expected_version) = match loaded {
            Some(snapshot) => (snapshot.data.try_into_domain()?, Some(snapshot.version)),
            None => (new_pool(&pool_id, req)?, None),
        };
        let interval = StationInterval::new(0, 1).map_err(to_internal)?;
        let unit_ref = pool
            .find_available_unit(&interval, now)
            .or_else(|| pool.capacity_unit_refs().into_iter().next())
            .ok_or_else(|| AppError::Unavailable("No capacity units available in pool".into()))?;
        let hold = CapacityHold::request(
            HoldId::new(&hold_id).map_err(to_internal)?,
            HoldScope::new(
                pool.identity.pool_id.clone(),
                unit_ref,
                interval,
                ReferenceMetadata::new(
                    "booking-orchestration",
                    "purchase-hold",
                    Some(req.segment_booking_id.clone()),
                    Some(req.segment_booking_id.clone()),
                    Some(req.traveler_ref.clone()),
                )
                .map_err(to_internal)?,
            ),
            IdempotencyKey::new(idempotency_key).map_err(to_internal)?,
            now,
            now + 300_000,
        )
        .map_err(to_internal)?;
        let events = pool.request_hold(hold, now).map_err(|error| match error {
            DomainError::IdempotencyConflict { .. } => AppError::IdempotencyKeyReused(
                "Idempotency-Key was reused with a different request body".into(),
            ),
            error => AppError::DomainRuleViolation(error.to_string()),
        })?;
        let envelopes = domain_events_to_wire(&events, correlation_id)?;
        self.inventory_repo
            .save(
                &mut tx,
                &pool_id,
                expected_version,
                &InventoryPoolSnapshot::from_domain(&pool),
            )
            .await
            .map_err(to_app_storage)?;
        let persisted = pool
            .hold(&HoldId::new(&hold_id).map_err(to_internal)?)
            .ok_or_else(|| AppError::Internal("hold was not stored in pool".into()))?;
        self.hold_repo
            .save(
                &mut tx,
                &hold_id,
                None,
                &CapacityHoldSnapshot::from_domain(persisted),
            )
            .await
            .map_err(to_app_storage)?;
        for envelope in envelopes {
            OutboxAppender::append(&mut tx, &stream_for_producer(PRODUCER), &envelope)
                .await
                .map_err(to_app_storage)?;
        }
        let status = if events
            .iter()
            .any(|event| matches!(event, DomainEvent::CapacityHeld(_)))
        {
            "HELD"
        } else {
            "WAITLISTED"
        };
        let response = HoldCapacityResponse {
            hold_id,
            segment_ref: req.segment_ref.clone(),
            status: status.to_string(),
            held_until: unix_millis_to_rfc3339(now + 300_000),
        };
        self.finish_idempotency(
            &mut tx,
            idempotency_key,
            fingerprint,
            201,
            serde_json::to_value(&response)
                .map_err(|error| AppError::Internal(error.to_string()))?,
        )
        .await?;
        tx.commit().await.map_err(to_app_storage)?;
        Ok(response)
    }

    pub async fn confirm_hold(
        &self,
        hold_id: &str,
        idempotency_key: &str,
        correlation_id: &str,
    ) -> Result<ConfirmHoldResponse, AppError> {
        let fingerprint = format!("confirm:{hold_id}");
        if let Some(resp) = self
            .idempotency_replay::<ConfirmHoldResponse>(idempotency_key, &fingerprint)
            .await?
        {
            return Ok(resp);
        }
        self.mutate_hold_pool(
            hold_id,
            idempotency_key,
            &fingerprint,
            200,
            |pool, now| {
                pool.confirm_hold(&HoldId::new(hold_id).map_err(to_internal)?, now)
                    .map_err(map_hold_mutation_error)
            },
            |_| ConfirmHoldResponse {
                hold_id: hold_id.to_string(),
                status: "CONFIRMED".to_string(),
            },
            correlation_id,
        )
        .await
    }

    pub async fn release_hold(
        &self,
        hold_id: &str,
        idempotency_key: &str,
        correlation_id: &str,
    ) -> Result<ReleaseHoldResponse, AppError> {
        self.release_hold_with_reason(
            hold_id,
            idempotency_key,
            correlation_id,
            "client-requested-release",
        )
        .await
    }

    async fn release_hold_with_reason(
        &self,
        hold_id: &str,
        idempotency_key: &str,
        correlation_id: &str,
        release_reason: &'static str,
    ) -> Result<ReleaseHoldResponse, AppError> {
        let fingerprint = format!("release:{hold_id}:{release_reason}");
        if let Some(resp) = self
            .idempotency_replay::<ReleaseHoldResponse>(idempotency_key, &fingerprint)
            .await?
        {
            return Ok(resp);
        }
        self.mutate_hold_pool(
            hold_id,
            idempotency_key,
            &fingerprint,
            200,
            |pool, now| {
                pool.release_hold(
                    &HoldId::new(hold_id).map_err(to_internal)?,
                    now,
                    release_reason,
                )
                .map_err(map_hold_mutation_error)
            },
            |_| ReleaseHoldResponse {
                hold_id: hold_id.to_string(),
                status: "RELEASED".to_string(),
            },
            correlation_id,
        )
        .await
    }

    async fn mutate_hold_pool<T, M, R>(
        &self,
        hold_id: &str,
        idempotency_key: &str,
        fingerprint: &str,
        status_code: u16,
        mutate: M,
        response: R,
        correlation_id: &str,
    ) -> Result<T, AppError>
    where
        T: Serialize + for<'de> Deserialize<'de>,
        M: Fn(&mut InventoryPool, u64) -> Result<Vec<DomainEvent>, AppError>,
        R: Fn(&[DomainEvent]) -> T,
    {
        for attempt in 0..MAX_RETRIES {
            let result = async {
                let hold_snapshot = self
                    .load_hold(hold_id)
                    .await?
                    .ok_or_else(|| AppError::NotFound("hold not found".into()))?;
                let pool_id = hold_snapshot.data.inventory_pool_id.clone();
                let mut tx = self.pool().begin().await.map_err(to_app_storage)?;
                if let Some(resp) = self
                    .claim_idempotency::<T>(&mut tx, idempotency_key, fingerprint)
                    .await?
                {
                    tx.commit().await.map_err(to_app_storage)?;
                    return Ok(resp);
                }
                let loaded_pool = self
                    .inventory_repo
                    .get::<InventoryPoolSnapshot>(&mut *tx, &pool_id)
                    .await
                    .map_err(to_app_storage)?
                    .ok_or_else(|| AppError::NotFound("hold not found".into()))?;
                let mut pool = loaded_pool.data.try_into_domain()?;
                let events = mutate(&mut pool, now_millis())?;
                let envelopes = domain_events_to_wire(&events, correlation_id)?;
                self.inventory_repo
                    .save(
                        &mut tx,
                        &pool_id,
                        Some(loaded_pool.version),
                        &InventoryPoolSnapshot::from_domain(&pool),
                    )
                    .await
                    .map_err(to_app_storage)?;
                let hold = pool
                    .hold(&HoldId::new(hold_id).map_err(to_internal)?)
                    .ok_or_else(|| AppError::NotFound("hold not found".into()))?;
                self.upsert_hold_snapshot(&mut tx, hold_id, hold).await?;
                for envelope in envelopes {
                    OutboxAppender::append(&mut tx, &stream_for_producer(PRODUCER), &envelope)
                        .await
                        .map_err(to_app_storage)?;
                }
                let resp = response(&events);
                self.finish_idempotency(
                    &mut tx,
                    idempotency_key,
                    fingerprint,
                    status_code,
                    serde_json::to_value(&resp)
                        .map_err(|error| AppError::Internal(error.to_string()))?,
                )
                .await?;
                tx.commit().await.map_err(to_app_storage)?;
                Ok(resp)
            }
            .await;
            match result {
                Err(AppError::Conflict(_)) if attempt + 1 < MAX_RETRIES => continue,
                result => return result,
            }
        }
        Err(AppError::Conflict("capacity hold write conflict".into()))
    }

    async fn upsert_hold_snapshot(
        &self,
        tx: &mut PgTransaction<'_>,
        hold_id: &str,
        hold: &CapacityHold,
    ) -> Result<(), AppError> {
        let existing = self
            .hold_repo
            .get::<CapacityHoldSnapshot>(&mut **tx, hold_id)
            .await
            .map_err(to_app_storage)?;
        self.hold_repo
            .save(
                tx,
                hold_id,
                existing.map(|snapshot| snapshot.version),
                &CapacityHoldSnapshot::from_domain(hold),
            )
            .await
            .map_err(to_app_storage)?;
        Ok(())
    }

    pub async fn get_hold(
        &self,
        hold_id: &str,
    ) -> Result<crate::application::GetHoldResponse, AppError> {
        let snapshot = self
            .load_hold(hold_id)
            .await?
            .ok_or_else(|| AppError::NotFound("hold not found".into()))?;
        let hold = snapshot.data.try_into_domain()?;
        let status = match hold.state {
            CapacityHoldState::Requested => "REQUESTED",
            CapacityHoldState::Held => "HELD",
            CapacityHoldState::Confirmed => "CONFIRMED",
            CapacityHoldState::Released => "RELEASED",
            CapacityHoldState::Expired => "EXPIRED",
            CapacityHoldState::Failed => "FAILED",
        };
        Ok(crate::application::GetHoldResponse {
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
            capacity_unit_ref: hold.scope.capacity_unit_ref.to_string(),
            from_seq: hold.scope.station_interval.from_seq(),
            to_seq: hold.scope.station_interval.to_seq(),
        })
    }

    async fn idempotency_replay<T: for<'de> Deserialize<'de>>(
        &self,
        idempotency_key: &str,
        fingerprint: &str,
    ) -> Result<Option<T>, AppError> {
        let store = DbIdempotencyStore::new(self.pool().clone());
        let Some(record) = store
            .get_async(idempotency_key)
            .await
            .map_err(to_app_storage)?
        else {
            return Ok(None);
        };
        if record.fingerprint != fingerprint {
            return Err(AppError::IdempotencyKeyReused(
                "Idempotency-Key was reused with a different request body".into(),
            ));
        }
        serde_json::from_value(record.body)
            .map(Some)
            .map_err(|_| AppError::Internal("Failed to deserialize cached response".into()))
    }

    async fn claim_idempotency<T: for<'de> Deserialize<'de>>(
        &self,
        tx: &mut PgTransaction<'_>,
        idempotency_key: &str,
        fingerprint: &str,
    ) -> Result<Option<T>, AppError> {
        match DbIdempotencyStore::claim_response(tx, idempotency_key, fingerprint)
            .await
            .map_err(to_app_storage)?
        {
            IdempotencyTxDecision::Claimed => Ok(None),
            IdempotencyTxDecision::Replay(record) => serde_json::from_value(record.body)
                .map(Some)
                .map_err(|_| AppError::Internal("Failed to deserialize cached response".into())),
        }
    }

    async fn finish_idempotency(
        &self,
        tx: &mut PgTransaction<'_>,
        idempotency_key: &str,
        fingerprint: &str,
        status_code: u16,
        response_body: Value,
    ) -> Result<(), AppError> {
        match DbIdempotencyStore::record_response(
            tx,
            idempotency_key,
            fingerprint,
            status_code,
            response_body,
        )
        .await
        .map_err(to_app_storage)?
        {
            IdempotencyTxDecision::Claimed => Ok(()),
            IdempotencyTxDecision::Replay(_) => Ok(()),
        }
    }

    async fn load_hold(
        &self,
        hold_id: &str,
    ) -> Result<Option<Snapshot<CapacityHoldSnapshot>>, AppError> {
        self.hold_repo
            .get::<CapacityHoldSnapshot>(self.pool(), hold_id)
            .await
            .map_err(to_app_storage)
    }

    async fn load_pools_for_availability_query(
        &self,
        scheduled_service_ref: &str,
        segment_ref: &str,
    ) -> Result<Vec<InventoryPool>, AppError> {
        let sql = format!(
            "SELECT data FROM {INVENTORY_TABLE} WHERE scheduled_service_ref = $1 OR service_segment_ref = $2 OR route_segment_ref = $2"
        );
        let rows: Vec<(Value,)> = sqlx::query_as(&sql)
            .bind(scheduled_service_ref)
            .bind(segment_ref)
            .fetch_all(self.pool())
            .await
            .map_err(to_app_storage)?;
        rows.into_iter()
            .map(|(data,)| {
                serde_json::from_value::<InventoryPoolSnapshot>(data)
                    .map_err(|error| AppError::Internal(error.to_string()))?
                    .try_into_domain()
            })
            .collect()
    }

    async fn find_releasable_hold_by_segment_booking(
        &self,
        tx: &mut PgTransaction<'_>,
        segment_booking_ref: &str,
    ) -> Result<Option<Snapshot<CapacityHoldSnapshot>>, StorageError> {
        let sql = format!(
            "SELECT id, version, data FROM {HOLD_TABLE} WHERE data #>> '{{references,segmentBookingRef}}' = $1 AND data ->> 'state' IN ('Held', 'Confirmed') ORDER BY updated_at DESC LIMIT 1"
        );
        let row: Option<(String, i64, Value)> = sqlx::query_as(&sql)
            .bind(segment_booking_ref)
            .fetch_optional(&mut **tx)
            .await?;
        row.map(|(id, version, data)| {
            Ok(Snapshot {
                id,
                version,
                data: serde_json::from_value(data)?,
            })
        })
        .transpose()
    }

    async fn find_confirmable_hold_by_segment_booking(
        &self,
        tx: &mut PgTransaction<'_>,
        segment_booking_ref: &str,
    ) -> Result<Option<Snapshot<CapacityHoldSnapshot>>, StorageError> {
        let sql = format!(
            "SELECT id, version, data FROM {HOLD_TABLE} WHERE data #>> '{{references,segmentBookingRef}}' = $1 AND data ->> 'state' = 'Held' ORDER BY updated_at DESC LIMIT 1"
        );
        let row: Option<(String, i64, Value)> = sqlx::query_as(&sql)
            .bind(segment_booking_ref)
            .fetch_optional(&mut **tx)
            .await?;
        row.map(|(id, version, data)| {
            Ok(Snapshot {
                id,
                version,
                data: serde_json::from_value(data)?,
            })
        })
        .transpose()
    }

    pub async fn handle_inbound_event(&self, envelope: WireEnvelope) -> HandlerResult {
        let result = match envelope.event_type.as_str() {
            "SegmentReservationRequested" => {
                self.handle_segment_reservation_requested(envelope).await
            }
            "SegmentReservationConfirmed" => {
                self.handle_segment_reservation_confirmed(envelope).await
            }
            "SegmentBookingCancelled" => self.handle_segment_booking_cancelled(envelope).await,
            "SegmentTicketed" => self.handle_segment_ticketed(envelope).await,
            "PostSalesApplied" => self.handle_post_sales_applied(envelope).await,
            "EntitlementVoided" => self.handle_entitlement_voided(envelope).await,
            _ => Ok(()),
        };
        match result {
            Ok(()) => HandlerResult::Success,
            Err(InboundEventError::Transient(message)) => HandlerResult::TransientError(message),
            Err(InboundEventError::Fatal(message)) => HandlerResult::FatalError(message),
        }
    }

    async fn handle_segment_reservation_requested(
        &self,
        envelope: WireEnvelope,
    ) -> Result<(), InboundEventError> {
        let Some(segment_booking_id) = string_field(&envelope.payload, "segmentBookingId") else {
            return Err(InboundEventError::Fatal("missing segmentBookingId".into()));
        };
        let Some(segment_ref) = string_field(&envelope.payload, "segmentRef") else {
            return Err(InboundEventError::Fatal("missing segmentRef".into()));
        };
        let Some(traveler_ref) = string_field(&envelope.payload, "travelerRef") else {
            return Err(InboundEventError::Fatal("missing travelerRef".into()));
        };
        let Some(_) = string_field(&envelope.payload, "journeyOrderId") else {
            return Err(InboundEventError::Fatal("missing journeyOrderId".into()));
        };
        let Some(idempotency_key) = string_field(&envelope.payload, "idempotencyKey") else {
            return Err(InboundEventError::Fatal("missing idempotencyKey".into()));
        };
        let request = HoldCapacityRequest {
            segment_ref,
            traveler_ref,
            class_ref: string_field(&envelope.payload, "classRef")
                .or_else(|| string_field(&envelope.payload, "seatClassRef"))
                .unwrap_or_else(|| "standard".to_string()),
            quantity: quantity_field(&envelope.payload).unwrap_or(1),
            segment_booking_id,
        };
        validate_hold_request(&request).map_err(inbound_from_app_error)?;
        let fingerprint = request.fingerprint();
        let stream = stream_for_producer(&envelope.producer);
        for attempt in 0..MAX_RETRIES {
            let result = self
                .try_handle_segment_reservation_requested(
                    &envelope,
                    &stream,
                    &request,
                    &idempotency_key,
                    &fingerprint,
                )
                .await;
            match result {
                Err(InboundEventError::Transient(message))
                    if message.contains("optimistic concurrency conflict")
                        && attempt + 1 < MAX_RETRIES =>
                {
                    continue;
                }
                result => return result,
            }
        }
        Err(InboundEventError::Transient(
            "capacity hold write conflict".into(),
        ))
    }

    async fn try_handle_segment_reservation_requested(
        &self,
        envelope: &WireEnvelope,
        stream: &str,
        req: &HoldCapacityRequest,
        idempotency_key: &str,
        fingerprint: &str,
    ) -> Result<(), InboundEventError> {
        let now = now_millis();
        let hold_id = format!("hold-{}", uuid::Uuid::now_v7());
        let pool_id = pool_id_for(&req.segment_ref, &req.class_ref);
        let mut tx = self.pool().begin().await.map_err(inbound_transient)?;
        if !mark_event_processing(&mut tx, &envelope.event_id, stream)
            .await
            .map_err(inbound_transient)?
        {
            tx.rollback().await.map_err(inbound_transient)?;
            return Ok(());
        }
        if let Some(replay) = self
            .find_replayable_hold_by_idempotency(&mut tx, idempotency_key, req)
            .await?
        {
            self.record_segment_reservation_replay(
                &mut tx,
                &stream_for_producer(PRODUCER),
                &replay,
                idempotency_key,
                fingerprint,
                &envelope.correlation_id,
            )
            .await?;
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        }
        if self
            .claim_idempotency::<HoldCapacityResponse>(&mut tx, idempotency_key, fingerprint)
            .await
            .map_err(inbound_from_app_error)?
            .is_some()
        {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        }
        let loaded = self
            .inventory_repo
            .get::<InventoryPoolSnapshot>(&mut *tx, &pool_id)
            .await
            .map_err(inbound_transient)?;
        let (mut pool, expected_version) = match loaded {
            Some(snapshot) => (
                snapshot
                    .data
                    .try_into_domain()
                    .map_err(inbound_from_app_error)?,
                Some(snapshot.version),
            ),
            None => (
                new_pool(&pool_id, req).map_err(inbound_from_app_error)?,
                None,
            ),
        };
        let interval = StationInterval::new(0, 1).map_err(inbound_fatal)?;
        let Some(unit_ref) = pool.find_available_unit(&interval, now) else {
            // Sold out is a business outcome, not an infrastructure failure:
            // retrying can never conjure a seat. Emit CapacityHoldFailed so the
            // saga compensates, and ack the message. capacityUnitRef carries the
            // documented sentinel NONE (contract: NO_AVAILABLE_CAPACITY).
            let failed = CapacityHoldFailed {
                envelope: EventEnvelope::new(
                    "CapacityHoldFailed",
                    now,
                    envelope.correlation_id.as_str(),
                    Some(envelope.event_id.as_str()),
                    "capacity-availability",
                ),
                requested_hold_id: HoldId::new(&hold_id).map_err(inbound_fatal)?,
                inventory_pool_id: pool.identity.pool_id.clone(),
                capacity_unit_ref: CapacityUnitRef::new("NONE").map_err(inbound_fatal)?,
                interval,
                idempotency_key: IdempotencyKey::new(idempotency_key).map_err(inbound_fatal)?,
                reason: HoldFailureReason::NoAvailableUnits,
                references: ReferenceMetadata::new(
                    "booking-orchestration",
                    "purchase-hold",
                    Some(req.segment_booking_id.clone()),
                    Some(req.segment_booking_id.clone()),
                    Some(req.traveler_ref.clone()),
                )
                .map_err(inbound_fatal)?,
            };
            let outbound = domain_event_to_wire(
                &DomainEvent::CapacityHoldFailed(failed),
                &envelope.correlation_id,
            )
            .map_err(inbound_from_app_error)?;
            OutboxAppender::append(&mut tx, &stream_for_producer(PRODUCER), &outbound)
                .await
                .map_err(inbound_transient)?;
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        };
        let hold = CapacityHold::request(
            HoldId::new(&hold_id).map_err(inbound_fatal)?,
            HoldScope::new(
                pool.identity.pool_id.clone(),
                unit_ref,
                interval,
                ReferenceMetadata::new(
                    "booking-orchestration",
                    "purchase-hold",
                    Some(req.segment_booking_id.clone()),
                    Some(req.segment_booking_id.clone()),
                    Some(req.traveler_ref.clone()),
                )
                .map_err(inbound_fatal)?,
            ),
            IdempotencyKey::new(idempotency_key).map_err(inbound_fatal)?,
            now,
            now + 300_000,
        )
        .map_err(inbound_fatal)?;
        let events = pool.request_hold(hold, now).map_err(|error| match error {
            DomainError::IdempotencyConflict { .. } => {
                InboundEventError::Fatal("IDEMPOTENCY_KEY_REUSED".into())
            }
            error => InboundEventError::Fatal(error.to_string()),
        })?;
        let outbound = domain_events_to_wire(&events, &envelope.correlation_id)
            .map_err(inbound_from_app_error)?;
        self.inventory_repo
            .save(
                &mut tx,
                &pool_id,
                expected_version,
                &InventoryPoolSnapshot::from_domain(&pool),
            )
            .await
            .map_err(inbound_transient)?;
        let persisted = pool
            .hold(&HoldId::new(&hold_id).map_err(inbound_fatal)?)
            .ok_or_else(|| InboundEventError::Transient("hold was not stored in pool".into()))?;
        self.hold_repo
            .save(
                &mut tx,
                &hold_id,
                None,
                &CapacityHoldSnapshot::from_domain(persisted),
            )
            .await
            .map_err(inbound_transient)?;
        for envelope in outbound {
            OutboxAppender::append(&mut tx, &stream_for_producer(PRODUCER), &envelope)
                .await
                .map_err(inbound_transient)?;
        }
        let status = if events
            .iter()
            .any(|event| matches!(event, DomainEvent::CapacityHeld(_)))
        {
            "HELD"
        } else {
            "WAITLISTED"
        };
        let response = HoldCapacityResponse {
            hold_id,
            segment_ref: req.segment_ref.clone(),
            status: status.to_string(),
            held_until: unix_millis_to_rfc3339(now + 300_000),
        };
        self.finish_idempotency(
            &mut tx,
            idempotency_key,
            fingerprint,
            201,
            serde_json::to_value(&response).map_err(inbound_transient)?,
        )
        .await
        .map_err(inbound_from_app_error)?;
        tx.commit().await.map_err(inbound_transient)?;
        Ok(())
    }

    async fn find_replayable_hold_by_idempotency(
        &self,
        tx: &mut PgTransaction<'_>,
        idempotency_key: &str,
        req: &HoldCapacityRequest,
    ) -> Result<Option<CapacityHoldSnapshot>, InboundEventError> {
        let sql = format!(
            "SELECT data FROM {HOLD_TABLE} WHERE data ->> 'idempotencyKey' = $1 AND data ->> 'state' IN ('Held', 'Confirmed') ORDER BY updated_at DESC LIMIT 1"
        );
        let row: Option<(Value,)> = sqlx::query_as(&sql)
            .bind(idempotency_key)
            .fetch_optional(&mut **tx)
            .await
            .map_err(inbound_transient)?;
        let Some((data,)) = row else {
            return Ok(None);
        };
        let snapshot: CapacityHoldSnapshot =
            serde_json::from_value(data).map_err(inbound_transient)?;
        if snapshot.matches_segment_reservation(req, idempotency_key) {
            Ok(Some(snapshot))
        } else {
            Err(InboundEventError::Fatal("IDEMPOTENCY_KEY_REUSED".into()))
        }
    }

    async fn record_segment_reservation_replay(
        &self,
        tx: &mut PgTransaction<'_>,
        outbox_stream: &str,
        hold: &CapacityHoldSnapshot,
        idempotency_key: &str,
        fingerprint: &str,
        correlation_id: &str,
    ) -> Result<(), InboundEventError> {
        let outbound = replay_capacity_held_envelope(hold, idempotency_key, correlation_id)?;
        OutboxAppender::append(tx, outbox_stream, &outbound)
            .await
            .map_err(inbound_transient)?;
        let response = HoldCapacityResponse {
            hold_id: hold.hold_id.clone(),
            segment_ref: hold.segment_ref(),
            status: "HELD".to_string(),
            held_until: unix_millis_to_rfc3339(hold.expires_at),
        };
        match self
            .finish_idempotency(
                tx,
                idempotency_key,
                fingerprint,
                201,
                serde_json::to_value(&response).map_err(inbound_transient)?,
            )
            .await
        {
            Ok(()) | Err(AppError::IdempotencyKeyReused(_)) => Ok(()),
            Err(error) => Err(inbound_from_app_error(error)),
        }
    }

    async fn handle_segment_reservation_confirmed(
        &self,
        envelope: WireEnvelope,
    ) -> Result<(), InboundEventError> {
        let Some(segment_booking_ref) = string_field(&envelope.payload, "segmentBookingId") else {
            return Err(InboundEventError::Fatal("missing segmentBookingId".into()));
        };
        let Some(_) = string_field(&envelope.payload, "evidence") else {
            return Err(InboundEventError::Fatal("missing evidence".into()));
        };
        let idempotency_key = format!("{}:{}:confirm", envelope.event_id, segment_booking_ref);
        let fingerprint = format!("confirm-by-segment-booking:{segment_booking_ref}");
        self.handle_inbound_segment_booking_confirm(
            envelope,
            &segment_booking_ref,
            &idempotency_key,
            &fingerprint,
        )
        .await
    }

    async fn handle_inbound_segment_booking_confirm(
        &self,
        envelope: WireEnvelope,
        segment_booking_ref: &str,
        idempotency_key: &str,
        fingerprint: &str,
    ) -> Result<(), InboundEventError> {
        let stream = stream_for_producer(&envelope.producer);
        for attempt in 0..MAX_RETRIES {
            let result = self
                .try_handle_inbound_segment_booking_confirm(
                    &envelope,
                    &stream,
                    segment_booking_ref,
                    idempotency_key,
                    fingerprint,
                )
                .await;
            match result {
                Err(InboundEventError::Transient(message))
                    if message.contains("optimistic concurrency conflict")
                        && attempt + 1 < MAX_RETRIES =>
                {
                    continue;
                }
                result => return result,
            }
        }
        Err(InboundEventError::Transient(
            "capacity hold write conflict".into(),
        ))
    }

    async fn try_handle_inbound_segment_booking_confirm(
        &self,
        envelope: &WireEnvelope,
        stream: &str,
        segment_booking_ref: &str,
        idempotency_key: &str,
        fingerprint: &str,
    ) -> Result<(), InboundEventError> {
        let mut tx = self.pool().begin().await.map_err(inbound_transient)?;
        if !mark_event_processing(&mut tx, &envelope.event_id, stream)
            .await
            .map_err(inbound_transient)?
        {
            tx.rollback().await.map_err(inbound_transient)?;
            return Ok(());
        }
        let Some(hold_snapshot) = self
            .find_confirmable_hold_by_segment_booking(&mut tx, segment_booking_ref)
            .await
            .map_err(inbound_transient)?
        else {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        };
        if self
            .claim_idempotency::<ConfirmHoldResponse>(&mut tx, idempotency_key, fingerprint)
            .await
            .map_err(inbound_from_app_error)?
            .is_some()
        {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        }
        let hold_id = hold_snapshot.id.clone();
        let pool_id = hold_snapshot.data.inventory_pool_id.clone();
        let Some(loaded_pool) = self
            .inventory_repo
            .get::<InventoryPoolSnapshot>(&mut *tx, &pool_id)
            .await
            .map_err(inbound_transient)?
        else {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        };
        let mut pool = loaded_pool
            .data
            .try_into_domain()
            .map_err(inbound_from_app_error)?;
        let hold_id_value = HoldId::new(&hold_id).map_err(inbound_fatal)?;
        let Some(current_hold) = pool.hold(&hold_id_value) else {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        };
        if !matches!(current_hold.state, CapacityHoldState::Held) {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        }
        let events = pool
            .confirm_hold(&hold_id_value, now_millis())
            .map_err(map_hold_mutation_error)
            .map_err(inbound_from_app_error)?;
        let outbound = domain_events_to_wire(&events, &envelope.correlation_id)
            .map_err(inbound_from_app_error)?;
        self.inventory_repo
            .save(
                &mut tx,
                &pool_id,
                Some(loaded_pool.version),
                &InventoryPoolSnapshot::from_domain(&pool),
            )
            .await
            .map_err(inbound_transient)?;
        let hold = pool
            .hold(&hold_id_value)
            .ok_or_else(|| InboundEventError::Fatal("hold not found".into()))?;
        self.upsert_hold_snapshot(&mut tx, &hold_id, hold)
            .await
            .map_err(inbound_from_app_error)?;
        for envelope in outbound {
            OutboxAppender::append(&mut tx, &stream_for_producer(PRODUCER), &envelope)
                .await
                .map_err(inbound_transient)?;
        }
        let resp = ConfirmHoldResponse {
            hold_id,
            status: "CONFIRMED".to_string(),
        };
        self.finish_idempotency(
            &mut tx,
            idempotency_key,
            fingerprint,
            200,
            serde_json::to_value(&resp).map_err(inbound_transient)?,
        )
        .await
        .map_err(inbound_from_app_error)?;
        tx.commit().await.map_err(inbound_transient)?;
        Ok(())
    }

    async fn handle_segment_booking_cancelled(
        &self,
        envelope: WireEnvelope,
    ) -> Result<(), InboundEventError> {
        let Some(segment_booking_ref) = string_field(&envelope.payload, "segmentBookingId") else {
            return Err(InboundEventError::Fatal("missing segmentBookingId".into()));
        };
        let Some(_) = string_field(&envelope.payload, "reason") else {
            return Err(InboundEventError::Fatal("missing reason".into()));
        };
        let idempotency_key = format!(
            "{}:{}:segment-booking-cancelled-release",
            envelope.event_id, segment_booking_ref
        );
        let fingerprint =
            format!("release-by-segment-booking:{segment_booking_ref}:segment-booking-cancelled");
        self.handle_inbound_segment_booking_release(
            envelope,
            &segment_booking_ref,
            &idempotency_key,
            &fingerprint,
            "segment-booking-cancelled",
        )
        .await
    }

    async fn handle_segment_ticketed(
        &self,
        envelope: WireEnvelope,
    ) -> Result<(), InboundEventError> {
        for field in ["segmentBookingId", "entitlementId"] {
            if string_field(&envelope.payload, field).is_none() {
                return Err(InboundEventError::Fatal(format!("missing {field}")));
            }
        }
        Ok(())
    }

    async fn handle_post_sales_applied(
        &self,
        envelope: WireEnvelope,
    ) -> Result<(), InboundEventError> {
        for field in ["caseId", "orderId"] {
            if string_field(&envelope.payload, field).is_none() {
                return Err(InboundEventError::Fatal(format!("missing {field}")));
            }
        }
        if envelope
            .payload
            .get("resultSummary")
            .is_none_or(Value::is_null)
        {
            return Err(InboundEventError::Fatal("missing resultSummary".into()));
        }
        let Some(segment_booking_ref) = post_sales_release_ref(&envelope.payload) else {
            return Ok(());
        };
        let idempotency_key = format!(
            "{}:{}:post-sales-applied-release",
            envelope.event_id, segment_booking_ref
        );
        let fingerprint =
            format!("release-by-segment-booking:{segment_booking_ref}:post-sales-applied");
        self.handle_inbound_segment_booking_release(
            envelope,
            &segment_booking_ref,
            &idempotency_key,
            &fingerprint,
            "post-sales-applied",
        )
        .await
    }

    async fn handle_entitlement_voided(
        &self,
        envelope: WireEnvelope,
    ) -> Result<(), InboundEventError> {
        let Some(segment_booking_ref) =
            segment_booking_ref_from_entitlement_voided(&envelope.payload)
        else {
            return Err(InboundEventError::Fatal("missing segmentBookingRef".into()));
        };
        for field in ["entitlementId", "voidedAt", "reason", "policy"] {
            if string_field(&envelope.payload, field).is_none() {
                return Err(InboundEventError::Fatal(format!("missing {field}")));
            }
        }
        let idempotency_key = format!(
            "{}:{}:entitlement-voided-release",
            envelope.event_id, segment_booking_ref
        );
        let fingerprint =
            format!("release-by-segment-booking:{segment_booking_ref}:entitlement-voided");
        self.handle_inbound_segment_booking_release(
            envelope,
            &segment_booking_ref,
            &idempotency_key,
            &fingerprint,
            "entitlement-voided",
        )
        .await
    }

    async fn handle_inbound_segment_booking_release(
        &self,
        envelope: WireEnvelope,
        segment_booking_ref: &str,
        idempotency_key: &str,
        fingerprint: &str,
        release_reason: &'static str,
    ) -> Result<(), InboundEventError> {
        let stream = stream_for_producer(&envelope.producer);
        for attempt in 0..MAX_RETRIES {
            let result = self
                .try_handle_inbound_segment_booking_release(
                    &envelope,
                    &stream,
                    segment_booking_ref,
                    idempotency_key,
                    fingerprint,
                    release_reason,
                )
                .await;
            match result {
                Err(InboundEventError::Transient(message))
                    if message.contains("optimistic concurrency conflict")
                        && attempt + 1 < MAX_RETRIES =>
                {
                    continue;
                }
                result => return result,
            }
        }
        Err(InboundEventError::Transient(
            "capacity hold write conflict".into(),
        ))
    }

    async fn try_handle_inbound_segment_booking_release(
        &self,
        envelope: &WireEnvelope,
        stream: &str,
        segment_booking_ref: &str,
        idempotency_key: &str,
        fingerprint: &str,
        release_reason: &'static str,
    ) -> Result<(), InboundEventError> {
        let mut tx = self.pool().begin().await.map_err(inbound_transient)?;
        if !mark_event_processing(&mut tx, &envelope.event_id, stream)
            .await
            .map_err(inbound_transient)?
        {
            tx.rollback().await.map_err(inbound_transient)?;
            return Ok(());
        }
        let Some(hold_snapshot) = self
            .find_releasable_hold_by_segment_booking(&mut tx, segment_booking_ref)
            .await
            .map_err(inbound_transient)?
        else {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        };
        if self
            .claim_idempotency::<ReleaseHoldResponse>(&mut tx, idempotency_key, fingerprint)
            .await
            .map_err(inbound_from_app_error)?
            .is_some()
        {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        }
        let hold_id = hold_snapshot.id.clone();
        let pool_id = hold_snapshot.data.inventory_pool_id.clone();
        let Some(loaded_pool) = self
            .inventory_repo
            .get::<InventoryPoolSnapshot>(&mut *tx, &pool_id)
            .await
            .map_err(inbound_transient)?
        else {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        };
        let mut pool = loaded_pool
            .data
            .try_into_domain()
            .map_err(inbound_from_app_error)?;
        let hold_id_value = HoldId::new(&hold_id).map_err(inbound_fatal)?;
        let Some(current_hold) = pool.hold(&hold_id_value) else {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        };
        if !matches!(
            current_hold.state,
            CapacityHoldState::Held | CapacityHoldState::Confirmed
        ) {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        }
        let events = pool
            .release_hold(&hold_id_value, now_millis(), release_reason)
            .map_err(map_hold_mutation_error)
            .map_err(inbound_from_app_error)?;
        let outbound = domain_events_to_wire(&events, &envelope.correlation_id)
            .map_err(inbound_from_app_error)?;
        self.inventory_repo
            .save(
                &mut tx,
                &pool_id,
                Some(loaded_pool.version),
                &InventoryPoolSnapshot::from_domain(&pool),
            )
            .await
            .map_err(inbound_transient)?;
        let hold = pool
            .hold(&hold_id_value)
            .ok_or_else(|| InboundEventError::Fatal("hold not found".into()))?;
        self.upsert_hold_snapshot(&mut tx, &hold_id, hold)
            .await
            .map_err(inbound_from_app_error)?;
        for envelope in outbound {
            OutboxAppender::append(&mut tx, &stream_for_producer(PRODUCER), &envelope)
                .await
                .map_err(inbound_transient)?;
        }
        let resp = ReleaseHoldResponse {
            hold_id,
            status: "RELEASED".to_string(),
        };
        self.finish_idempotency(
            &mut tx,
            idempotency_key,
            fingerprint,
            200,
            serde_json::to_value(&resp).map_err(inbound_transient)?,
        )
        .await
        .map_err(inbound_from_app_error)?;
        tx.commit().await.map_err(inbound_transient)?;
        Ok(())
    }

    #[allow(dead_code)]
    async fn handle_inbound_hold_mutation<T, M, R>(
        &self,
        envelope: WireEnvelope,
        hold_id: &str,
        idempotency_key: &str,
        fingerprint: &str,
        status_code: u16,
        mutate: M,
        response: R,
    ) -> Result<(), InboundEventError>
    where
        T: Serialize + for<'de> Deserialize<'de>,
        M: Fn(&mut InventoryPool, u64) -> Result<Vec<DomainEvent>, AppError>,
        R: Fn(&[DomainEvent]) -> T,
    {
        let stream = stream_for_producer(&envelope.producer);
        for attempt in 0..MAX_RETRIES {
            let result = self
                .try_handle_inbound_hold_mutation(
                    &envelope,
                    &stream,
                    hold_id,
                    idempotency_key,
                    fingerprint,
                    status_code,
                    &mutate,
                    &response,
                )
                .await;
            match result {
                Err(InboundEventError::Transient(message))
                    if message.contains("optimistic concurrency conflict")
                        && attempt + 1 < MAX_RETRIES =>
                {
                    continue;
                }
                result => return result,
            }
        }
        Err(InboundEventError::Transient(
            "capacity hold write conflict".into(),
        ))
    }

    #[allow(dead_code)]
    async fn try_handle_inbound_hold_mutation<T, M, R>(
        &self,
        envelope: &WireEnvelope,
        stream: &str,
        hold_id: &str,
        idempotency_key: &str,
        fingerprint: &str,
        status_code: u16,
        mutate: &M,
        response: &R,
    ) -> Result<(), InboundEventError>
    where
        T: Serialize + for<'de> Deserialize<'de>,
        M: Fn(&mut InventoryPool, u64) -> Result<Vec<DomainEvent>, AppError>,
        R: Fn(&[DomainEvent]) -> T,
    {
        let mut tx = self.pool().begin().await.map_err(inbound_transient)?;
        if !mark_event_processing(&mut tx, &envelope.event_id, stream)
            .await
            .map_err(inbound_transient)?
        {
            tx.rollback().await.map_err(inbound_transient)?;
            return Ok(());
        }
        if self
            .claim_idempotency::<T>(&mut tx, idempotency_key, fingerprint)
            .await
            .map_err(inbound_from_app_error)?
            .is_some()
        {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        }
        let hold_snapshot = self
            .hold_repo
            .get::<CapacityHoldSnapshot>(&mut *tx, hold_id)
            .await
            .map_err(inbound_transient)?;
        let Some(hold_snapshot) = hold_snapshot else {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        };
        let pool_id = hold_snapshot.data.inventory_pool_id.clone();
        let loaded_pool = self
            .inventory_repo
            .get::<InventoryPoolSnapshot>(&mut *tx, &pool_id)
            .await
            .map_err(inbound_transient)?;
        let Some(loaded_pool) = loaded_pool else {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        };
        let mut pool = loaded_pool
            .data
            .try_into_domain()
            .map_err(inbound_from_app_error)?;
        let events = match mutate(&mut pool, now_millis()) {
            Ok(events) => events,
            Err(AppError::NotFound(_)) | Err(AppError::PreconditionFailed(_)) => {
                tx.commit().await.map_err(inbound_transient)?;
                return Ok(());
            }
            Err(error) => return Err(inbound_from_app_error(error)),
        };
        let outbound = domain_events_to_wire(&events, &envelope.correlation_id)
            .map_err(inbound_from_app_error)?;
        self.inventory_repo
            .save(
                &mut tx,
                &pool_id,
                Some(loaded_pool.version),
                &InventoryPoolSnapshot::from_domain(&pool),
            )
            .await
            .map_err(inbound_transient)?;
        let hold = pool
            .hold(&HoldId::new(hold_id).map_err(inbound_fatal)?)
            .ok_or_else(|| InboundEventError::Fatal("hold not found".into()))?;
        self.upsert_hold_snapshot(&mut tx, hold_id, hold)
            .await
            .map_err(inbound_from_app_error)?;
        for envelope in outbound {
            OutboxAppender::append(&mut tx, &stream_for_producer(PRODUCER), &envelope)
                .await
                .map_err(inbound_transient)?;
        }
        let resp = response(&events);
        self.finish_idempotency(
            &mut tx,
            idempotency_key,
            fingerprint,
            status_code,
            serde_json::to_value(&resp).map_err(inbound_transient)?,
        )
        .await
        .map_err(inbound_from_app_error)?;
        tx.commit().await.map_err(inbound_transient)?;
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum InboundEventError {
    Transient(String),
    Fatal(String),
}

fn inbound_transient(error: impl std::fmt::Display) -> InboundEventError {
    InboundEventError::Transient(error.to_string())
}

fn inbound_fatal(error: impl std::fmt::Display) -> InboundEventError {
    InboundEventError::Fatal(error.to_string())
}

fn inbound_from_app_error(error: AppError) -> InboundEventError {
    match error {
        AppError::Unavailable(message) | AppError::Internal(message) => {
            InboundEventError::Transient(message)
        }
        AppError::Conflict(message) if message.contains("optimistic concurrency conflict") => {
            InboundEventError::Transient(message)
        }
        error => InboundEventError::Fatal(error.message().to_string()),
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InventoryPoolSnapshot {
    identity: InventoryPoolIdentitySnapshot,
    capacity_units: Vec<String>,
    holds: Vec<CapacityHoldSnapshot>,
    overbooking_policy: Option<OverbookingPolicy>,
}

impl InventoryPoolSnapshot {
    fn from_domain(pool: &InventoryPool) -> Self {
        let mut capacity_units: Vec<_> = pool
            .capacity_unit_refs()
            .into_iter()
            .map(|unit| unit.to_string())
            .collect();
        capacity_units.sort();
        let mut holds: Vec<_> = pool
            .holds()
            .into_iter()
            .map(CapacityHoldSnapshot::from_domain)
            .collect();
        holds.sort_by(|left, right| left.hold_id.cmp(&right.hold_id));
        Self {
            identity: InventoryPoolIdentitySnapshot::from_domain(&pool.identity),
            capacity_units,
            holds,
            overbooking_policy: Some(pool.overbooking_policy()),
        }
    }

    fn try_into_domain(self) -> Result<InventoryPool, AppError> {
        let mut pool = InventoryPool::new(
            self.identity.try_into_domain()?,
            self.capacity_units
                .into_iter()
                .map(CapacityUnitRef::new)
                .collect::<Result<Vec<_>, _>>()
                .map_err(to_internal)?,
        )
        .map_err(to_internal)?;
        if let Some(policy) = self.overbooking_policy {
            pool.set_overbooking_policy(policy);
        }
        for hold in self.holds {
            pool.restore_hold(hold.try_into_domain()?)
                .map_err(to_internal)?;
        }
        Ok(pool)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InventoryPoolIdentitySnapshot {
    pool_id: String,
    scheduled_service_ref: String,
    service_date: String,
    seat_class_or_cabin_ref: String,
    sellable_unit_type_ref: String,
    route_segment_ref: String,
    service_segment_ref: String,
    stop_sequence_version: String,
}

impl InventoryPoolIdentitySnapshot {
    fn from_domain(identity: &InventoryPoolIdentity) -> Self {
        Self {
            pool_id: identity.pool_id.to_string(),
            scheduled_service_ref: identity.scheduled_service_ref.clone(),
            service_date: identity.service_date.clone(),
            seat_class_or_cabin_ref: identity.seat_class_or_cabin_ref.clone(),
            sellable_unit_type_ref: identity.sellable_unit_type_ref.clone(),
            route_segment_ref: identity.route_segment_ref.clone(),
            service_segment_ref: identity.service_segment_ref.clone(),
            stop_sequence_version: identity.stop_sequence_version.clone(),
        }
    }

    fn try_into_domain(self) -> Result<InventoryPoolIdentity, AppError> {
        InventoryPoolIdentity::new(
            self.pool_id,
            self.scheduled_service_ref,
            self.service_date,
            self.seat_class_or_cabin_ref,
            self.sellable_unit_type_ref,
            self.route_segment_ref,
            self.service_segment_ref,
            self.stop_sequence_version,
        )
        .map_err(to_internal)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CapacityHoldSnapshot {
    hold_id: String,
    inventory_pool_id: String,
    capacity_unit_ref: String,
    from_seq: u32,
    to_seq: u32,
    idempotency_key: String,
    requested_at: u64,
    expires_at: u64,
    state: String,
    confirmed_at: Option<u64>,
    released_at: Option<u64>,
    expired_at: Option<u64>,
    references: ReferenceMetadataSnapshot,
}

impl CapacityHoldSnapshot {
    fn from_domain(hold: &CapacityHold) -> Self {
        Self {
            hold_id: hold.hold_id.to_string(),
            inventory_pool_id: hold.scope.inventory_pool_id.to_string(),
            capacity_unit_ref: hold.scope.capacity_unit_ref.to_string(),
            from_seq: hold.scope.station_interval.from_seq(),
            to_seq: hold.scope.station_interval.to_seq(),
            idempotency_key: hold.idempotency_key.to_string(),
            requested_at: hold.requested_at,
            expires_at: hold.expires_at,
            state: format!("{:?}", hold.state),
            confirmed_at: hold.confirmed_at,
            released_at: hold.released_at,
            expired_at: hold.expired_at,
            references: ReferenceMetadataSnapshot::from_domain(&hold.scope.references),
        }
    }

    fn matches_segment_reservation(
        &self,
        req: &HoldCapacityRequest,
        idempotency_key: &str,
    ) -> bool {
        self.idempotency_key == idempotency_key
            && self.inventory_pool_id == pool_id_for(&req.segment_ref, &req.class_ref)
            && self.segment_ref() == req.segment_ref
            && self.references.traveler_ref.as_deref() == Some(req.traveler_ref.as_str())
            && self.references.segment_booking_ref.as_deref()
                == Some(req.segment_booking_id.as_str())
    }

    fn segment_ref(&self) -> String {
        self.inventory_pool_id
            .strip_prefix("pool:")
            .and_then(|tail| tail.rsplit_once(':').map(|(segment_ref, _)| segment_ref))
            .unwrap_or(&self.inventory_pool_id)
            .to_string()
    }

    fn try_into_domain(self) -> Result<CapacityHold, AppError> {
        let mut hold = CapacityHold::request(
            HoldId::new(self.hold_id).map_err(to_internal)?,
            HoldScope::new(
                InventoryPoolId::new(self.inventory_pool_id).map_err(to_internal)?,
                CapacityUnitRef::new(self.capacity_unit_ref).map_err(to_internal)?,
                StationInterval::new(self.from_seq, self.to_seq).map_err(to_internal)?,
                self.references.try_into_domain()?,
            ),
            IdempotencyKey::new(self.idempotency_key).map_err(to_internal)?,
            self.requested_at,
            self.expires_at,
        )
        .map_err(to_internal)?;
        hold.restore_state(
            parse_state(&self.state)?,
            self.confirmed_at,
            self.released_at,
            self.expired_at,
        );
        Ok(hold)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReferenceMetadataSnapshot {
    source_context: String,
    order_ref: Option<String>,
    segment_booking_ref: Option<String>,
    traveler_ref: Option<String>,
    reason: String,
}

impl ReferenceMetadataSnapshot {
    fn from_domain(references: &ReferenceMetadata) -> Self {
        Self {
            source_context: references.source_context.clone(),
            order_ref: references.order_ref.clone(),
            segment_booking_ref: references.segment_booking_ref.clone(),
            traveler_ref: references.traveler_ref.clone(),
            reason: references.reason.clone(),
        }
    }

    fn try_into_domain(self) -> Result<ReferenceMetadata, AppError> {
        ReferenceMetadata::new(
            self.source_context,
            self.reason,
            self.order_ref,
            self.segment_booking_ref,
            self.traveler_ref,
        )
        .map_err(to_internal)
    }
}

fn new_pool(pool_id: &str, req: &HoldCapacityRequest) -> Result<InventoryPool, AppError> {
    let identity = InventoryPoolIdentity::new(
        pool_id.to_string(),
        format!("scheduled-service:{}", req.segment_ref),
        "2026-07-05",
        &req.class_ref,
        "seat",
        &req.segment_ref,
        &req.segment_ref,
        "v1",
    )
    .map_err(to_internal)?;
    let units = (0..req.quantity.max(100))
        .map(|i| CapacityUnitRef::new(format!("{:02}{}", (i / 4) + 1, ['A', 'B', 'C', 'D'][i % 4])))
        .collect::<Result<Vec<_>, _>>()
        .map_err(to_internal)?;
    InventoryPool::new(identity, units)
        .map(|pool| {
            pool.with_overbooking_policy(OverbookingPolicy {
                max_overbooking_pct: 5.0,
                no_show_rate: 3.0,
                safety_margin_pct: 1.0,
            })
        })
        .map_err(to_internal)
}

fn validate_hold_request(req: &HoldCapacityRequest) -> Result<(), AppError> {
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
    Ok(())
}

fn pool_id_for(segment_ref: &str, class_ref: &str) -> String {
    format!("pool:{}:{}", segment_ref, class_ref)
}

fn replay_capacity_held_envelope(
    hold: &CapacityHoldSnapshot,
    idempotency_key: &str,
    correlation_id: &str,
) -> Result<WireEnvelope, InboundEventError> {
    WireEnvelope::try_new(
        "CapacityHeld",
        unix_millis_to_rfc3339(now_millis()),
        rust_kit::messaging::valid_or_generated_correlation_id(correlation_id),
        None::<String>,
        PRODUCER,
        json!({
            "holdId": hold.hold_id,
            "inventoryPoolId": hold.inventory_pool_id,
            "capacityUnitRef": hold.capacity_unit_ref,
            "interval": { "fromSeq": hold.from_seq, "toSeq": hold.to_seq },
            "idempotencyKey": idempotency_key,
            "expiresAt": unix_millis_to_rfc3339(hold.expires_at),
            "idempotentReplay": true,
        }),
    )
    .map_err(inbound_fatal)
}

fn domain_events_to_wire(
    events: &[DomainEvent],
    correlation_id: &str,
) -> Result<Vec<WireEnvelope>, AppError> {
    events
        .iter()
        .map(|event| domain_event_to_wire(event, correlation_id))
        .collect()
}

fn domain_event_to_wire(
    event: &DomainEvent,
    correlation_id: &str,
) -> Result<WireEnvelope, AppError> {
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
                "segmentRef": e.service_segment_ref,
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
        DomainEvent::CapacityHoldFailed(e) => {
            let mut payload = json!({
                "requestedHoldId": e.requested_hold_id.to_string(),
                "inventoryPoolId": e.inventory_pool_id.to_string(),
                "capacityUnitRef": e.capacity_unit_ref.to_string(),
                "interval": { "fromSeq": e.interval.from_seq(), "toSeq": e.interval.to_seq() },
                "idempotencyKey": e.idempotency_key.to_string(),
                "reason": e.reason.contract_reason(),
            });
            if let Some(conflicting_hold_id) = e.reason.conflicting_hold_id() {
                payload["conflictingHoldId"] = json!(conflicting_hold_id.to_string());
            }
            ("CapacityHoldFailed", payload)
        }
        DomainEvent::CapacitySnapshotUpdated(e) => (
            "CapacitySnapshotUpdated",
            capacity_snapshot_payload(&e.snapshot),
        ),
        DomainEvent::OverbookingThresholdReached(e) => (
            "OverbookingThresholdReached",
            json!({
                "poolId": e.pool_id.to_string(),
                "physicalCapacity": e.physical_capacity,
                "confirmedCount": e.confirmed_count,
                "overbookingPct": e.overbooking_pct,
            }),
        ),
        DomainEvent::WaitlistActivated(e) => (
            "WaitlistActivated",
            json!({
                "segmentRef": e.segment_ref,
                "departureDate": e.departure_date,
                "queuePosition": e.queue_position,
            }),
        ),
        DomainEvent::WaitlistCapacityFreed(e) => (
            "WaitlistCapacityFreed",
            json!({
                "segmentRef": e.segment_ref,
                "departureDate": e.departure_date,
                "freedSlots": e.freed_slots,
            }),
        ),
    };
    WireEnvelope::try_new(
        event_type,
        unix_millis_to_rfc3339(now_millis()),
        rust_kit::messaging::valid_or_generated_correlation_id(correlation_id),
        None::<String>,
        PRODUCER,
        payload,
    )
    .map_err(|error| AppError::Internal(error.to_string()))
}

fn capacity_snapshot_payload(snapshot: &CapacitySnapshot) -> Value {
    json!({
        "segmentRef": snapshot.segment_ref,
        "departureDate": snapshot.departure_date,
        "totalCapacity": snapshot.total_capacity,
        "remainingCapacity": snapshot.remaining_capacity,
        "physicalCapacity": snapshot.physical_capacity,
        "holdCount": snapshot.hold_count,
        "confirmedCount": snapshot.confirmed_count,
        "utilizationPct": snapshot.utilization_pct,
        "snapshotVersion": snapshot.snapshot_version,
        "classes": snapshot.classes.iter().map(|class| json!({
            "classRef": class.class_ref,
            "physicalCapacity": class.physical_capacity,
            "effectiveCapacity": class.effective_capacity,
            "holdCount": class.hold_count,
            "confirmedCount": class.confirmed_count,
            "remainingCapacity": class.remaining_capacity,
            "overbookingPolicy": {
                "maxOverbookingPct": class.overbooking_policy.max_overbooking_pct,
                "noShowRate": class.overbooking_policy.no_show_rate,
                "safetyMarginPct": class.overbooking_policy.safety_margin_pct,
            }
        })).collect::<Vec<_>>(),
    })
}

fn parse_state(state: &str) -> Result<CapacityHoldState, AppError> {
    match state {
        "Requested" => Ok(CapacityHoldState::Requested),
        "Held" => Ok(CapacityHoldState::Held),
        "Confirmed" => Ok(CapacityHoldState::Confirmed),
        "Released" => Ok(CapacityHoldState::Released),
        "Expired" => Ok(CapacityHoldState::Expired),
        "Failed" => Ok(CapacityHoldState::Failed),
        _ => Err(AppError::Internal(format!("unknown hold state {state}"))),
    }
}

fn map_hold_mutation_error(error: DomainError) -> AppError {
    match error {
        DomainError::UnknownHold(_) => AppError::NotFound("hold not found".into()),
        DomainError::InvalidHoldState { .. } => {
            AppError::PreconditionFailed("Hold is not in a state that can be mutated".into())
        }
        error => AppError::DomainRuleViolation(error.to_string()),
    }
}

fn to_internal(error: impl std::fmt::Display) -> AppError {
    AppError::Internal(error.to_string())
}

fn to_app_storage(error: impl std::fmt::Display) -> AppError {
    let message = error.to_string();
    if message.contains("optimistic concurrency conflict") {
        AppError::Conflict(message)
    } else if message == "IDEMPOTENCY_KEY_REUSED" || message.contains("IDEMPOTENCY_KEY_REUSED") {
        AppError::IdempotencyKeyReused(
            "Idempotency-Key was reused with a different request body".into(),
        )
    } else {
        AppError::Unavailable(message)
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

fn segment_booking_ref_from_entitlement_voided(value: &Value) -> Option<String> {
    value
        .get("references")
        .and_then(|references| string_field(references, "segmentBookingRef"))
        .or_else(|| string_field(value, "segmentBookingId"))
}

fn post_sales_release_ref(value: &Value) -> Option<String> {
    value
        .get("references")
        .and_then(|references| {
            string_field(references, "segmentBookingRef")
                .or_else(|| string_field(references, "segmentBookingId"))
                .or_else(|| string_field(references, "capacityHoldId"))
                .or_else(|| string_field(references, "holdId"))
        })
        .or_else(|| {
            value.get("scope").and_then(|scope| {
                string_field(scope, "segmentBookingRef")
                    .or_else(|| string_field(scope, "segmentBookingId"))
                    .or_else(|| string_field(scope, "capacityHoldId"))
                    .or_else(|| string_field(scope, "holdId"))
            })
        })
        .or_else(|| string_field(value, "segmentBookingRef"))
        .or_else(|| string_field(value, "segmentBookingId"))
        .or_else(|| string_field(value, "capacityHoldId"))
        .or_else(|| string_field(value, "holdId"))
}

fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request() -> HoldCapacityRequest {
        HoldCapacityRequest {
            segment_ref: "seg".into(),
            traveler_ref: "traveler".into(),
            class_ref: "first".into(),
            quantity: 1,
            segment_booking_id: "booking".into(),
        }
    }

    fn replay_snapshot(
        req: &HoldCapacityRequest,
        hold_id: &str,
        idempotency_key: &str,
    ) -> CapacityHoldSnapshot {
        let now = now_millis();
        let hold = CapacityHold::request(
            HoldId::new(hold_id).unwrap(),
            HoldScope::new(
                InventoryPoolId::new("pool:seg:first").unwrap(),
                CapacityUnitRef::new("unit-1").unwrap(),
                StationInterval::new(0, 1).unwrap(),
                ReferenceMetadata::new(
                    "booking-orchestration",
                    "purchase-hold",
                    Some(req.segment_booking_id.clone()),
                    Some(req.segment_booking_id.clone()),
                    Some(req.traveler_ref.clone()),
                )
                .unwrap(),
            ),
            IdempotencyKey::new(idempotency_key).unwrap(),
            now,
            now + 300_000,
        )
        .unwrap();
        CapacityHoldSnapshot::from_domain(&hold)
    }

    #[test]
    fn pool_snapshot_round_trips_holds() {
        let req = request();
        let mut pool = new_pool("pool:seg:first", &req).unwrap();
        let now = now_millis();
        let interval = StationInterval::new(0, 1).unwrap();
        let unit = pool.find_available_unit(&interval, now).unwrap();
        let hold = CapacityHold::request(
            HoldId::new("hold-1").unwrap(),
            HoldScope::new(
                pool.identity.pool_id.clone(),
                unit,
                interval,
                ReferenceMetadata::new(
                    "booking",
                    "reason",
                    Some("order"),
                    Some("seg-booking"),
                    Some("traveler"),
                )
                .unwrap(),
            ),
            IdempotencyKey::new("idem-1").unwrap(),
            now,
            now + 1_000,
        )
        .unwrap();
        pool.request_hold(hold, now).unwrap();

        let restored = InventoryPoolSnapshot::from_domain(&pool)
            .try_into_domain()
            .unwrap();
        assert_eq!(restored.total_units(), pool.total_units());
        assert!(restored.hold(&HoldId::new("hold-1").unwrap()).is_some());
    }

    #[test]
    fn stale_expected_version_maps_to_conflict_for_retry_path() {
        let err = to_app_storage(StorageError::Conflict(
            "snapshot pool version 1 was not current".into(),
        ));
        assert!(matches!(err, AppError::Conflict(_)));
    }

    #[test]
    fn entitlement_voided_segment_booking_ref_prefers_references_shape() {
        let payload = json!({
            "entitlementId": "ent-1",
            "segmentBookingId": "legacy-sb",
            "references": {
                "segmentBookingRef": "sb-1"
            }
        });

        assert_eq!(
            segment_booking_ref_from_entitlement_voided(&payload),
            Some("sb-1".to_string())
        );
    }

    #[test]
    fn entitlement_voided_segment_booking_ref_accepts_legacy_direct_field() {
        let payload = json!({
            "entitlementId": "ent-1",
            "segmentBookingId": "sb-legacy"
        });

        assert_eq!(
            segment_booking_ref_from_entitlement_voided(&payload),
            Some("sb-legacy".to_string())
        );
    }

    #[test]
    fn replay_snapshot_matches_original_segment_reservation() {
        let req = request();
        let snapshot = replay_snapshot(&req, "hold-replay", "idem-replay");

        assert!(snapshot.matches_segment_reservation(&req, "idem-replay"));
        assert_eq!(snapshot.segment_ref(), req.segment_ref);
        assert!(!snapshot.matches_segment_reservation(&req, "different-key"));
    }

    #[test]
    fn replay_capacity_held_envelope_marks_idempotent_replay() {
        let req = request();
        let snapshot = replay_snapshot(&req, "hold-replay-envelope", "idem-replay-envelope");
        let envelope = replay_capacity_held_envelope(
            &snapshot,
            "idem-replay-envelope",
            "corr-0194f2e0-7b3e-7610-0284-5c26e8b0fa44",
        )
        .unwrap();

        assert_eq!(envelope.event_type, "CapacityHeld");
        assert_eq!(envelope.producer, PRODUCER);
        assert_eq!(envelope.payload["holdId"], "hold-replay-envelope");
        assert_eq!(envelope.payload["idempotencyKey"], "idem-replay-envelope");
        assert_eq!(envelope.payload["idempotentReplay"], true);
    }

    #[test]
    fn optimistic_concurrency_conflict_path_retries_and_applies_second_write() {
        let req = request();
        let mut committed_pool = new_pool("pool:seg:first", &req).unwrap();
        let stale_snapshot = InventoryPoolSnapshot::from_domain(&committed_pool);
        let fresh_snapshot = stale_snapshot.clone();
        let mut stale_writer = stale_snapshot.try_into_domain().unwrap();
        let mut fresh_writer = fresh_snapshot.try_into_domain().unwrap();
        let now = now_millis();
        let interval = StationInterval::new(0, 1).unwrap();

        let fresh_unit = fresh_writer.find_available_unit(&interval, now).unwrap();
        let fresh_hold = CapacityHold::request(
            HoldId::new("hold-fresh").unwrap(),
            HoldScope::new(
                fresh_writer.identity.pool_id.clone(),
                fresh_unit,
                interval.clone(),
                ReferenceMetadata::new(
                    "booking",
                    "reason",
                    None::<String>,
                    Some("fresh"),
                    None::<String>,
                )
                .unwrap(),
            ),
            IdempotencyKey::new("idem-fresh").unwrap(),
            now,
            now + 1_000,
        )
        .unwrap();
        fresh_writer.request_hold(fresh_hold, now).unwrap();
        let mut stored_version = 2_i64;
        committed_pool = fresh_writer;

        let stale_unit = stale_writer.find_available_unit(&interval, now).unwrap();
        let stale_hold = CapacityHold::request(
            HoldId::new("hold-stale").unwrap(),
            HoldScope::new(
                stale_writer.identity.pool_id.clone(),
                stale_unit,
                interval.clone(),
                ReferenceMetadata::new(
                    "booking",
                    "reason",
                    None::<String>,
                    Some("stale"),
                    None::<String>,
                )
                .unwrap(),
            ),
            IdempotencyKey::new("idem-stale").unwrap(),
            now,
            now + 1_000,
        )
        .unwrap();
        stale_writer.request_hold(stale_hold, now).unwrap();
        let stale_expected_version = 1_i64;
        assert_eq!(stale_expected_version + 1, stored_version);
        let stale_update_rows = i64::from(stale_expected_version == stored_version);
        assert_eq!(
            stale_update_rows, 0,
            "stale writer must observe a real 0-row optimistic update"
        );

        let mut retry_writer = InventoryPoolSnapshot::from_domain(&committed_pool)
            .try_into_domain()
            .unwrap();
        let retry_unit = retry_writer.find_available_unit(&interval, now).unwrap();
        let retry_hold = CapacityHold::request(
            HoldId::new("hold-stale").unwrap(),
            HoldScope::new(
                retry_writer.identity.pool_id.clone(),
                retry_unit,
                interval,
                ReferenceMetadata::new(
                    "booking",
                    "reason",
                    None::<String>,
                    Some("stale"),
                    None::<String>,
                )
                .unwrap(),
            ),
            IdempotencyKey::new("idem-stale").unwrap(),
            now,
            now + 1_000,
        )
        .unwrap();
        retry_writer.request_hold(retry_hold, now).unwrap();
        let retry_expected_version = stored_version;
        let retry_update_rows = i64::from(retry_expected_version == stored_version);
        assert_eq!(retry_update_rows, 1);
        stored_version += 1;
        committed_pool = retry_writer;

        assert_eq!(stored_version, 3);
        assert!(
            committed_pool
                .hold(&HoldId::new("hold-fresh").unwrap())
                .is_some()
        );
        assert!(
            committed_pool
                .hold(&HoldId::new("hold-stale").unwrap())
                .is_some()
        );
    }

    #[tokio::test]
    #[ignore = "requires TEST_DATABASE_URL pointing at a disposable Postgres database"]
    async fn optimistic_concurrency_conflict_uses_real_zero_row_update() {
        let database_url =
            std::env::var("TEST_DATABASE_URL").expect("TEST_DATABASE_URL is required");
        let pool = sqlx::postgres::PgPoolOptions::new()
            .max_connections(2)
            .connect(&database_url)
            .await
            .unwrap();
        let table = format!(
            "test_inventory_pool_snapshots_{}",
            uuid::Uuid::now_v7().simple()
        );
        sqlx::query(&format!(
            "CREATE TABLE {table} (id text PRIMARY KEY, version bigint NOT NULL, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())"
        ))
        .execute(&pool)
        .await
        .unwrap();
        let repo = SnapshotRepository::new(&table).unwrap();
        let req = request();
        let pool_id = "pool:seg:first";
        let snapshot = InventoryPoolSnapshot::from_domain(&new_pool(pool_id, &req).unwrap());
        let mut setup = pool.begin().await.unwrap();
        repo.save(&mut setup, pool_id, None, &snapshot)
            .await
            .unwrap();
        setup.commit().await.unwrap();

        let mut tx1 = pool.begin().await.unwrap();
        let mut tx2 = pool.begin().await.unwrap();
        let loaded1 = repo
            .get::<InventoryPoolSnapshot>(&mut *tx1, pool_id)
            .await
            .unwrap()
            .unwrap();
        let loaded2 = repo
            .get::<InventoryPoolSnapshot>(&mut *tx2, pool_id)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(loaded1.version, loaded2.version);

        repo.save(&mut tx1, pool_id, Some(loaded1.version), &loaded1.data)
            .await
            .unwrap();
        tx1.commit().await.unwrap();

        let stale = repo
            .save(&mut tx2, pool_id, Some(loaded2.version), &loaded2.data)
            .await;
        assert!(matches!(stale, Err(StorageError::Conflict(_))));
        tx2.rollback().await.unwrap();

        let mut retry = pool.begin().await.unwrap();
        let current = repo
            .get::<InventoryPoolSnapshot>(&mut *retry, pool_id)
            .await
            .unwrap()
            .unwrap();
        let new_version = repo
            .save(&mut retry, pool_id, Some(current.version), &current.data)
            .await
            .unwrap();
        retry.commit().await.unwrap();
        assert_eq!(new_version, current.version + 1);

        sqlx::query(&format!("DROP TABLE {table}"))
            .execute(&pool)
            .await
            .unwrap();
    }
}
