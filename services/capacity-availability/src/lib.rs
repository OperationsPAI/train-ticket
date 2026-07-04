use axum::Router;
use serde::Serialize;
use shared_kernel::{RuntimeConfig, apply_runtime, router_with_config};
use std::collections::{HashMap, HashSet};

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ServiceProfile {
    pub service_id: &'static str,
    pub domain: &'static str,
    pub language: &'static str,
    pub phase: &'static str,
    pub requirement: &'static str,
    pub owns: &'static [&'static str],
}

pub fn profile() -> ServiceProfile {
    ServiceProfile {
        service_id: "capacity-availability",
        domain: "Capacity & Availability",
        language: "rust",
        phase: "phase-1-core",
        requirement: "REQ-006 Capacity & Availability domain foundation",
        owns: &[
            "InventoryPool",
            "StationInterval",
            "CapacityHold",
            "AvailabilitySnapshot",
        ],
    }
}

pub fn health() -> &'static str {
    "ok"
}

pub fn metadata() -> ServiceProfile {
    profile()
}

pub fn runtime_config() -> RuntimeConfig {
    RuntimeConfig::from_metadata(metadata())
}

pub fn router() -> Router {
    router_with_config(runtime_config())
}

pub fn apply_service_runtime(router: Router) -> Router {
    apply_runtime(router, runtime_config())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DomainError {
    EmptyField(&'static str),
    InvalidStationInterval {
        from_seq: u32,
        to_seq: u32,
    },
    DuplicateCapacityUnit(String),
    UnknownCapacityUnit(String),
    UnknownHold(String),
    IdempotencyConflict {
        idempotency_key: String,
        existing_hold_id: String,
    },
    HoldConflict {
        requested_hold_id: String,
        conflicting_hold_id: String,
        capacity_unit_ref: String,
        interval: StationInterval,
    },
    InvalidHoldState {
        hold_id: String,
        state: CapacityHoldState,
        command: &'static str,
    },
    HoldExpiredBeforeRequest {
        requested_at: u64,
        expires_at: u64,
    },
    HoldNotExpired {
        hold_id: String,
        now: u64,
        expires_at: u64,
    },
}

pub type DomainResult<T> = Result<T, DomainError>;

fn non_empty(value: impl Into<String>, field: &'static str) -> DomainResult<String> {
    let value = value.into();
    if value.trim().is_empty() {
        Err(DomainError::EmptyField(field))
    } else {
        Ok(value)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct InventoryPoolId(String);

impl InventoryPoolId {
    pub fn new(value: impl Into<String>) -> DomainResult<Self> {
        Ok(Self(non_empty(value, "inventory_pool_id")?))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for InventoryPoolId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct HoldId(String);

impl HoldId {
    pub fn new(value: impl Into<String>) -> DomainResult<Self> {
        Ok(Self(non_empty(value, "hold_id")?))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for HoldId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct CapacityUnitRef(String);

impl CapacityUnitRef {
    pub fn new(value: impl Into<String>) -> DomainResult<Self> {
        Ok(Self(non_empty(value, "capacity_unit_ref")?))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for CapacityUnitRef {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct IdempotencyKey(String);

impl IdempotencyKey {
    pub fn new(value: impl Into<String>) -> DomainResult<Self> {
        Ok(Self(non_empty(value, "idempotency_key")?))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for IdempotencyKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct StationInterval {
    from_seq: u32,
    to_seq: u32,
}

impl StationInterval {
    /// Build a half-open train station interval `[from_seq, to_seq)`.
    pub fn new(from_seq: u32, to_seq: u32) -> DomainResult<Self> {
        if from_seq >= to_seq {
            return Err(DomainError::InvalidStationInterval { from_seq, to_seq });
        }
        Ok(Self { from_seq, to_seq })
    }

    pub fn from_seq(&self) -> u32 {
        self.from_seq
    }

    pub fn to_seq(&self) -> u32 {
        self.to_seq
    }

    pub fn overlaps(&self, other: &Self) -> bool {
        self.from_seq < other.to_seq && other.from_seq < self.to_seq
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct InventoryPoolIdentity {
    pub pool_id: InventoryPoolId,
    /// Cross-context reference to Service Plan's scheduled service. This service
    /// stores the reference only and does not import Service Plan internals.
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub seat_class_or_cabin_ref: String,
    pub sellable_unit_type_ref: String,
    /// Cross-context reference to Place & Network route/service segment.
    pub route_segment_ref: String,
    /// Cross-context reference to Service Plan's segment publication.
    pub service_segment_ref: String,
    pub stop_sequence_version: String,
}

impl InventoryPoolIdentity {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        pool_id: impl Into<String>,
        scheduled_service_ref: impl Into<String>,
        service_date: impl Into<String>,
        seat_class_or_cabin_ref: impl Into<String>,
        sellable_unit_type_ref: impl Into<String>,
        route_segment_ref: impl Into<String>,
        service_segment_ref: impl Into<String>,
        stop_sequence_version: impl Into<String>,
    ) -> DomainResult<Self> {
        Ok(Self {
            pool_id: InventoryPoolId::new(pool_id)?,
            scheduled_service_ref: non_empty(scheduled_service_ref, "scheduled_service_ref")?,
            service_date: non_empty(service_date, "service_date")?,
            seat_class_or_cabin_ref: non_empty(seat_class_or_cabin_ref, "seat_class_or_cabin_ref")?,
            sellable_unit_type_ref: non_empty(sellable_unit_type_ref, "sellable_unit_type_ref")?,
            route_segment_ref: non_empty(route_segment_ref, "route_segment_ref")?,
            service_segment_ref: non_empty(service_segment_ref, "service_segment_ref")?,
            stop_sequence_version: non_empty(stop_sequence_version, "stop_sequence_version")?,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReferenceMetadata {
    pub source_context: String,
    pub order_ref: Option<String>,
    pub segment_booking_ref: Option<String>,
    pub traveler_ref: Option<String>,
    pub reason: String,
}

impl ReferenceMetadata {
    pub fn new(
        source_context: impl Into<String>,
        reason: impl Into<String>,
        order_ref: Option<impl Into<String>>,
        segment_booking_ref: Option<impl Into<String>>,
        traveler_ref: Option<impl Into<String>>,
    ) -> DomainResult<Self> {
        Ok(Self {
            source_context: non_empty(source_context, "source_context")?,
            order_ref: order_ref.map(Into::into).filter(|v| !v.trim().is_empty()),
            segment_booking_ref: segment_booking_ref
                .map(Into::into)
                .filter(|v| !v.trim().is_empty()),
            traveler_ref: traveler_ref
                .map(Into::into)
                .filter(|v| !v.trim().is_empty()),
            reason: non_empty(reason, "reason")?,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HoldScope {
    pub inventory_pool_id: InventoryPoolId,
    pub capacity_unit_ref: CapacityUnitRef,
    pub station_interval: StationInterval,
    pub references: ReferenceMetadata,
}

impl HoldScope {
    pub fn new(
        inventory_pool_id: InventoryPoolId,
        capacity_unit_ref: CapacityUnitRef,
        station_interval: StationInterval,
        references: ReferenceMetadata,
    ) -> Self {
        Self {
            inventory_pool_id,
            capacity_unit_ref,
            station_interval,
            references,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CapacityHoldState {
    Requested,
    Held,
    Confirmed,
    Released,
    Expired,
    Failed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CapacityHold {
    pub hold_id: HoldId,
    pub scope: HoldScope,
    pub idempotency_key: IdempotencyKey,
    pub requested_at: u64,
    pub expires_at: u64,
    pub state: CapacityHoldState,
    pub confirmed_at: Option<u64>,
    pub released_at: Option<u64>,
    pub expired_at: Option<u64>,
}

impl CapacityHold {
    pub fn request(
        hold_id: HoldId,
        scope: HoldScope,
        idempotency_key: IdempotencyKey,
        requested_at: u64,
        expires_at: u64,
    ) -> DomainResult<Self> {
        if expires_at <= requested_at {
            return Err(DomainError::HoldExpiredBeforeRequest {
                requested_at,
                expires_at,
            });
        }
        Ok(Self {
            hold_id,
            scope,
            idempotency_key,
            requested_at,
            expires_at,
            state: CapacityHoldState::Requested,
            confirmed_at: None,
            released_at: None,
            expired_at: None,
        })
    }

    pub fn is_blocking_at(&self, now: u64) -> bool {
        match self.state {
            CapacityHoldState::Held => now < self.expires_at,
            CapacityHoldState::Confirmed => true,
            CapacityHoldState::Requested
            | CapacityHoldState::Released
            | CapacityHoldState::Expired
            | CapacityHoldState::Failed => false,
        }
    }

    fn mark_held(&mut self) {
        self.state = CapacityHoldState::Held;
    }

    fn confirm(&mut self, now: u64) -> DomainResult<()> {
        match self.state {
            CapacityHoldState::Held => {
                self.state = CapacityHoldState::Confirmed;
                self.confirmed_at = Some(now);
                Ok(())
            }
            _ => Err(DomainError::InvalidHoldState {
                hold_id: self.hold_id.to_string(),
                state: self.state,
                command: "ConfirmHold",
            }),
        }
    }

    fn release(&mut self, now: u64) -> DomainResult<()> {
        match self.state {
            CapacityHoldState::Held | CapacityHoldState::Confirmed => {
                self.state = CapacityHoldState::Released;
                self.released_at = Some(now);
                Ok(())
            }
            CapacityHoldState::Released => Ok(()),
            _ => Err(DomainError::InvalidHoldState {
                hold_id: self.hold_id.to_string(),
                state: self.state,
                command: "ReleaseHold",
            }),
        }
    }

    fn expire(&mut self, now: u64) -> DomainResult<()> {
        match self.state {
            CapacityHoldState::Held if now >= self.expires_at => {
                self.state = CapacityHoldState::Expired;
                self.expired_at = Some(now);
                Ok(())
            }
            CapacityHoldState::Held => Err(DomainError::HoldNotExpired {
                hold_id: self.hold_id.to_string(),
                now,
                expires_at: self.expires_at,
            }),
            CapacityHoldState::Expired => Ok(()),
            _ => Err(DomainError::InvalidHoldState {
                hold_id: self.hold_id.to_string(),
                state: self.state,
                command: "ExpireHold",
            }),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DomainEvent {
    CapacityHeld(CapacityHeld),
    CapacityHoldConfirmed(CapacityHoldConfirmed),
    CapacityReleased(CapacityReleased),
    CapacityHoldExpired(CapacityHoldExpired),
    CapacityHoldFailed(CapacityHoldFailed),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CapacityHeld {
    pub hold_id: HoldId,
    pub inventory_pool_id: InventoryPoolId,
    pub capacity_unit_ref: CapacityUnitRef,
    pub interval: StationInterval,
    pub idempotency_key: IdempotencyKey,
    pub expires_at: u64,
    pub references: ReferenceMetadata,
    pub idempotent_replay: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CapacityHoldConfirmed {
    pub hold_id: HoldId,
    pub inventory_pool_id: InventoryPoolId,
    pub capacity_unit_ref: CapacityUnitRef,
    pub interval: StationInterval,
    pub confirmed_at: u64,
    pub references: ReferenceMetadata,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CapacityReleased {
    pub hold_id: HoldId,
    pub inventory_pool_id: InventoryPoolId,
    pub capacity_unit_ref: CapacityUnitRef,
    pub interval: StationInterval,
    pub released_at: u64,
    pub release_reason: String,
    pub references: ReferenceMetadata,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CapacityHoldExpired {
    pub hold_id: HoldId,
    pub inventory_pool_id: InventoryPoolId,
    pub capacity_unit_ref: CapacityUnitRef,
    pub interval: StationInterval,
    pub expired_at: u64,
    pub references: ReferenceMetadata,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CapacityHoldFailed {
    pub requested_hold_id: HoldId,
    pub inventory_pool_id: InventoryPoolId,
    pub capacity_unit_ref: CapacityUnitRef,
    pub interval: StationInterval,
    pub idempotency_key: IdempotencyKey,
    pub reason: HoldFailureReason,
    pub references: ReferenceMetadata,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HoldFailureReason {
    UnknownCapacityUnit,
    IdempotencyConflict { existing_hold_id: HoldId },
    OverlappingHold { conflicting_hold_id: HoldId },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InventoryPoolState {
    Initialized,
    OpenForSale,
    Frozen,
    ClosedForSale,
    Cancelled,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InventoryPool {
    pub identity: InventoryPoolIdentity,
    capacity_units: HashSet<CapacityUnitRef>,
    holds: HashMap<HoldId, CapacityHold>,
    idempotency_index: HashMap<IdempotencyKey, HoldId>,
    version: u64,
    state: InventoryPoolState,
}

impl InventoryPool {
    pub fn new(
        identity: InventoryPoolIdentity,
        capacity_units: impl IntoIterator<Item = CapacityUnitRef>,
    ) -> DomainResult<Self> {
        let mut unique = HashSet::new();
        for unit in capacity_units {
            if !unique.insert(unit.clone()) {
                return Err(DomainError::DuplicateCapacityUnit(unit.to_string()));
            }
        }
        Ok(Self {
            identity,
            capacity_units: unique,
            holds: HashMap::new(),
            idempotency_index: HashMap::new(),
            version: 1,
            state: InventoryPoolState::Initialized,
        })
    }

    pub fn version(&self) -> u64 {
        self.version
    }

    pub fn state(&self) -> InventoryPoolState {
        self.state
    }

    pub fn total_units(&self) -> usize {
        self.capacity_units.len()
    }

    pub fn hold(&self, hold_id: &HoldId) -> Option<&CapacityHold> {
        self.holds.get(hold_id)
    }

    pub fn request_hold(&mut self, mut hold: CapacityHold, now: u64) -> DomainResult<DomainEvent> {
        self.ensure_unit_known(&hold.scope.capacity_unit_ref)?;

        if let Some(existing_hold_id) = self.idempotency_index.get(&hold.idempotency_key) {
            let existing = self
                .holds
                .get(existing_hold_id)
                .expect("idempotency index references an existing hold");
            if same_hold_request(existing, &hold) {
                return Ok(DomainEvent::CapacityHeld(existing.to_capacity_held(true)));
            }
            return Err(DomainError::IdempotencyConflict {
                idempotency_key: hold.idempotency_key.to_string(),
                existing_hold_id: existing_hold_id.to_string(),
            });
        }

        if let Some(conflict) = self.conflicting_hold(
            &hold.hold_id,
            &hold.scope.capacity_unit_ref,
            &hold.scope.station_interval,
            now,
        ) {
            let event = CapacityHoldFailed {
                requested_hold_id: hold.hold_id.clone(),
                inventory_pool_id: self.identity.pool_id.clone(),
                capacity_unit_ref: hold.scope.capacity_unit_ref.clone(),
                interval: hold.scope.station_interval.clone(),
                idempotency_key: hold.idempotency_key.clone(),
                reason: HoldFailureReason::OverlappingHold {
                    conflicting_hold_id: conflict.hold_id.clone(),
                },
                references: hold.scope.references.clone(),
            };
            hold.state = CapacityHoldState::Failed;
            self.holds.insert(hold.hold_id.clone(), hold);
            self.version += 1;
            return Ok(DomainEvent::CapacityHoldFailed(event));
        }

        hold.mark_held();
        let event = DomainEvent::CapacityHeld(hold.to_capacity_held(false));
        self.idempotency_index
            .insert(hold.idempotency_key.clone(), hold.hold_id.clone());
        self.holds.insert(hold.hold_id.clone(), hold);
        self.version += 1;
        Ok(event)
    }

    pub fn confirm_hold(&mut self, hold_id: &HoldId, now: u64) -> DomainResult<DomainEvent> {
        let event = {
            let hold = self
                .holds
                .get_mut(hold_id)
                .ok_or_else(|| DomainError::UnknownHold(hold_id.to_string()))?;
            hold.confirm(now)?;
            DomainEvent::CapacityHoldConfirmed(CapacityHoldConfirmed {
                hold_id: hold.hold_id.clone(),
                inventory_pool_id: hold.scope.inventory_pool_id.clone(),
                capacity_unit_ref: hold.scope.capacity_unit_ref.clone(),
                interval: hold.scope.station_interval.clone(),
                confirmed_at: now,
                references: hold.scope.references.clone(),
            })
        };
        self.version += 1;
        Ok(event)
    }

    pub fn release_hold(
        &mut self,
        hold_id: &HoldId,
        now: u64,
        release_reason: impl Into<String>,
    ) -> DomainResult<DomainEvent> {
        let release_reason = non_empty(release_reason, "release_reason")?;
        let event = {
            let hold = self
                .holds
                .get_mut(hold_id)
                .ok_or_else(|| DomainError::UnknownHold(hold_id.to_string()))?;
            hold.release(now)?;
            DomainEvent::CapacityReleased(CapacityReleased {
                hold_id: hold.hold_id.clone(),
                inventory_pool_id: hold.scope.inventory_pool_id.clone(),
                capacity_unit_ref: hold.scope.capacity_unit_ref.clone(),
                interval: hold.scope.station_interval.clone(),
                released_at: now,
                release_reason,
                references: hold.scope.references.clone(),
            })
        };
        self.version += 1;
        Ok(event)
    }

    pub fn expire_hold(&mut self, hold_id: &HoldId, now: u64) -> DomainResult<DomainEvent> {
        let event = {
            let hold = self
                .holds
                .get_mut(hold_id)
                .ok_or_else(|| DomainError::UnknownHold(hold_id.to_string()))?;
            hold.expire(now)?;
            DomainEvent::CapacityHoldExpired(CapacityHoldExpired {
                hold_id: hold.hold_id.clone(),
                inventory_pool_id: hold.scope.inventory_pool_id.clone(),
                capacity_unit_ref: hold.scope.capacity_unit_ref.clone(),
                interval: hold.scope.station_interval.clone(),
                expired_at: now,
                references: hold.scope.references.clone(),
            })
        };
        self.version += 1;
        Ok(event)
    }

    pub fn availability_snapshot(
        &self,
        requested_interval: StationInterval,
        generated_at: u64,
        valid_until: u64,
    ) -> AvailabilitySnapshot {
        let occupied: HashSet<CapacityUnitRef> = self
            .holds
            .values()
            .filter(|hold| {
                hold.is_blocking_at(generated_at)
                    && hold.scope.station_interval.overlaps(&requested_interval)
            })
            .map(|hold| hold.scope.capacity_unit_ref.clone())
            .collect();

        let mut available_units: Vec<CapacityUnitRef> = self
            .capacity_units
            .difference(&occupied)
            .cloned()
            .collect::<Vec<_>>();
        available_units.sort_by(|a, b| a.as_str().cmp(b.as_str()));

        let available_count = available_units.len();
        let total_units = self.capacity_units.len();
        let status = if total_units == 0 || available_count == 0 {
            AvailabilityStatus::SoldOut
        } else if available_count <= 2 || available_count * 4 <= total_units {
            AvailabilityStatus::LowAvailability
        } else {
            AvailabilityStatus::Available
        };
        let explanations = match status {
            AvailabilityStatus::Available => vec![AvailabilityExplanation::InventoryAvailable],
            AvailabilityStatus::LowAvailability => vec![AvailabilityExplanation::LowAvailability],
            AvailabilityStatus::SoldOut => vec![AvailabilityExplanation::NoUnitsAvailable],
        };

        AvailabilitySnapshot {
            inventory_pool_id: self.identity.pool_id.clone(),
            requested_interval,
            source_version: self.version,
            generated_at,
            valid_until,
            total_units,
            available_count,
            available_units,
            status,
            explanations,
        }
    }

    fn ensure_unit_known(&self, unit: &CapacityUnitRef) -> DomainResult<()> {
        if self.capacity_units.contains(unit) {
            Ok(())
        } else {
            Err(DomainError::UnknownCapacityUnit(unit.to_string()))
        }
    }

    fn conflicting_hold(
        &self,
        requested_hold_id: &HoldId,
        unit: &CapacityUnitRef,
        interval: &StationInterval,
        now: u64,
    ) -> Option<&CapacityHold> {
        self.holds.values().find(|existing| {
            existing.hold_id != *requested_hold_id
                && existing.scope.capacity_unit_ref == *unit
                && existing.is_blocking_at(now)
                && existing.scope.station_interval.overlaps(interval)
        })
    }
}

fn same_hold_request(existing: &CapacityHold, requested: &CapacityHold) -> bool {
    existing.hold_id == requested.hold_id
        && existing.scope == requested.scope
        && existing.expires_at == requested.expires_at
}

impl CapacityHold {
    fn to_capacity_held(&self, idempotent_replay: bool) -> CapacityHeld {
        CapacityHeld {
            hold_id: self.hold_id.clone(),
            inventory_pool_id: self.scope.inventory_pool_id.clone(),
            capacity_unit_ref: self.scope.capacity_unit_ref.clone(),
            interval: self.scope.station_interval.clone(),
            idempotency_key: self.idempotency_key.clone(),
            expires_at: self.expires_at,
            references: self.scope.references.clone(),
            idempotent_replay,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AvailabilitySnapshot {
    pub inventory_pool_id: InventoryPoolId,
    pub requested_interval: StationInterval,
    pub source_version: u64,
    pub generated_at: u64,
    pub valid_until: u64,
    pub total_units: usize,
    pub available_count: usize,
    pub available_units: Vec<CapacityUnitRef>,
    pub status: AvailabilityStatus,
    pub explanations: Vec<AvailabilityExplanation>,
}

impl AvailabilitySnapshot {
    pub fn is_expired_at(&self, now: u64) -> bool {
        now >= self.valid_until
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AvailabilityStatus {
    Available,
    LowAvailability,
    SoldOut,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AvailabilityExplanation {
    InventoryAvailable,
    LowAvailability,
    NoUnitsAvailable,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn unit(value: &str) -> CapacityUnitRef {
        CapacityUnitRef::new(value).unwrap()
    }

    fn hold_id(value: &str) -> HoldId {
        HoldId::new(value).unwrap()
    }

    fn idempotency(value: &str) -> IdempotencyKey {
        IdempotencyKey::new(value).unwrap()
    }

    fn metadata() -> ReferenceMetadata {
        ReferenceMetadata::new(
            "booking-orchestration",
            "purchase-hold",
            Some("order-1"),
            Some("segment-booking-1"),
            Some("traveler-1"),
        )
        .unwrap()
    }

    fn identity() -> InventoryPoolIdentity {
        InventoryPoolIdentity::new(
            "pool-G123-2026-07-03-first-class",
            "scheduled-service:G123:2026-07-03",
            "2026-07-03",
            "seat-class:first",
            "seat",
            "route-segment:sha-nkg",
            "service-segment:G123:sha-nkg",
            "stops-v7",
        )
        .unwrap()
    }

    fn pool() -> InventoryPool {
        InventoryPool::new(identity(), [unit("01A"), unit("01B")]).unwrap()
    }

    fn request(
        id: &str,
        unit_ref: &str,
        from: u32,
        to: u32,
        key: &str,
        requested_at: u64,
        expires_at: u64,
    ) -> CapacityHold {
        CapacityHold::request(
            hold_id(id),
            HoldScope::new(
                InventoryPoolId::new("pool-G123-2026-07-03-first-class").unwrap(),
                unit(unit_ref),
                StationInterval::new(from, to).unwrap(),
                metadata(),
            ),
            idempotency(key),
            requested_at,
            expires_at,
        )
        .unwrap()
    }

    #[test]
    fn skeleton_profile_matches_domain() {
        let profile = profile();
        assert_eq!(profile.service_id, "capacity-availability");
        assert_eq!(profile.domain, "Capacity & Availability");
        assert_eq!(
            profile.requirement,
            "REQ-006 Capacity & Availability domain foundation"
        );
        assert_eq!(health(), "ok");
    }

    #[test]
    fn axum_router_can_be_constructed() {
        let _router = router();
    }

    #[tokio::test]
    async fn standard_runtime_endpoints_and_request_ids_are_available() {
        use axum::body::Body;
        use axum::http::{Request, StatusCode};
        use shared_kernel::{CORRELATION_ID_HEADER, REQUEST_ID_HEADER};
        use tower::ServiceExt;

        for path in [
            "/health",
            "/live",
            "/livez",
            "/ready",
            "/readyz",
            "/metadata",
        ] {
            let response = router()
                .oneshot(
                    Request::builder()
                        .uri(path)
                        .header(REQUEST_ID_HEADER, "capacity-req")
                        .body(Body::empty())
                        .unwrap(),
                )
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::OK, "{path}");
            assert_eq!(response.headers()[REQUEST_ID_HEADER], "capacity-req");
            assert_eq!(response.headers()[CORRELATION_ID_HEADER], "capacity-req");
        }
    }

    #[test]
    fn station_interval_is_half_open_and_validated() {
        assert!(StationInterval::new(3, 3).is_err());
        assert!(StationInterval::new(4, 3).is_err());
        let a_to_c = StationInterval::new(1, 3).unwrap();
        let c_to_e = StationInterval::new(3, 5).unwrap();
        let b_to_d = StationInterval::new(2, 4).unwrap();
        assert!(!a_to_c.overlaps(&c_to_e));
        assert!(a_to_c.overlaps(&b_to_d));
    }

    #[test]
    fn overlapping_holds_on_same_unit_are_rejected() {
        let mut pool = pool();
        let first = request("hold-1", "01A", 1, 3, "idem-1", 10, 70);
        assert!(matches!(
            pool.request_hold(first, 10).unwrap(),
            DomainEvent::CapacityHeld(_)
        ));

        let overlap = request("hold-2", "01A", 2, 4, "idem-2", 11, 71);
        let failed = pool.request_hold(overlap, 11).unwrap();
        match failed {
            DomainEvent::CapacityHoldFailed(event) => assert!(matches!(
                event.reason,
                HoldFailureReason::OverlappingHold { .. }
            )),
            _ => panic!("expected capacity hold failure event"),
        }
    }

    #[test]
    fn adjacent_intervals_and_different_units_do_not_conflict() {
        let mut pool = pool();
        pool.request_hold(request("hold-1", "01A", 1, 3, "idem-1", 10, 70), 10)
            .unwrap();
        pool.request_hold(request("hold-2", "01A", 3, 5, "idem-2", 11, 71), 11)
            .unwrap();
        pool.request_hold(request("hold-3", "01B", 2, 4, "idem-3", 12, 72), 12)
            .unwrap();
        assert_eq!(pool.holds.len(), 3);
    }

    #[test]
    fn hold_lifecycle_confirm_release_and_expire() {
        let mut pool = pool();
        pool.request_hold(request("hold-1", "01A", 1, 3, "idem-1", 10, 70), 10)
            .unwrap();
        let confirmed = pool.confirm_hold(&hold_id("hold-1"), 20).unwrap();
        assert!(matches!(confirmed, DomainEvent::CapacityHoldConfirmed(_)));
        assert!(pool.expire_hold(&hold_id("hold-1"), 80).is_err());
        let released = pool
            .release_hold(&hold_id("hold-1"), 90, "post-sales-void")
            .unwrap();
        assert!(matches!(released, DomainEvent::CapacityReleased(_)));

        pool.request_hold(request("hold-2", "01A", 1, 3, "idem-2", 100, 110), 100)
            .unwrap();
        assert!(pool.expire_hold(&hold_id("hold-2"), 109).is_err());
        let expired = pool.expire_hold(&hold_id("hold-2"), 110).unwrap();
        assert!(matches!(expired, DomainEvent::CapacityHoldExpired(_)));
    }

    #[test]
    fn request_hold_is_idempotent_for_identical_command() {
        let mut pool = pool();
        let first = request("hold-1", "01A", 1, 3, "idem-1", 10, 70);
        pool.request_hold(first.clone(), 10).unwrap();
        let replay = pool.request_hold(first, 11).unwrap();
        match replay {
            DomainEvent::CapacityHeld(event) => assert!(event.idempotent_replay),
            _ => panic!("expected held replay"),
        }

        let conflict = request("hold-2", "01A", 3, 5, "idem-1", 12, 72);
        assert!(matches!(
            pool.request_hold(conflict, 12).unwrap_err(),
            DomainError::IdempotencyConflict { .. }
        ));
    }

    #[test]
    fn availability_snapshot_counts_without_locking_inventory() {
        let mut pool = pool();
        pool.request_hold(request("hold-1", "01A", 1, 4, "idem-1", 10, 70), 10)
            .unwrap();

        let snapshot = pool.availability_snapshot(StationInterval::new(2, 3).unwrap(), 20, 30);
        assert_eq!(snapshot.total_units, 2);
        assert_eq!(snapshot.available_count, 1);
        assert_eq!(snapshot.available_units, vec![unit("01B")]);
        assert_eq!(snapshot.status, AvailabilityStatus::LowAvailability);
        assert_eq!(pool.holds.len(), 1, "snapshot must not create a hold");

        let after_expiry = pool.availability_snapshot(StationInterval::new(2, 3).unwrap(), 70, 80);
        assert_eq!(after_expiry.available_count, 2);
    }
}
