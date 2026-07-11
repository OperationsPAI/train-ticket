use std::collections::{HashMap, HashSet};

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

impl std::fmt::Display for DomainError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DomainError::EmptyField(field) => write!(f, "empty field: {}", field),
            DomainError::InvalidStationInterval { from_seq, to_seq } => {
                write!(f, "invalid station interval: {} >= {}", from_seq, to_seq)
            }
            DomainError::DuplicateCapacityUnit(u) => write!(f, "duplicate capacity unit: {}", u),
            DomainError::UnknownCapacityUnit(u) => write!(f, "unknown capacity unit: {}", u),
            DomainError::UnknownHold(id) => write!(f, "unknown hold: {}", id),
            DomainError::IdempotencyConflict {
                idempotency_key,
                existing_hold_id,
            } => {
                write!(
                    f,
                    "idempotency conflict: key={} existing_hold={}",
                    idempotency_key, existing_hold_id
                )
            }
            DomainError::HoldConflict {
                requested_hold_id,
                conflicting_hold_id,
                capacity_unit_ref,
                interval,
            } => {
                write!(
                    f,
                    "hold conflict: requested={} conflicts with {} on unit {} interval {:?}",
                    requested_hold_id, conflicting_hold_id, capacity_unit_ref, interval
                )
            }
            DomainError::InvalidHoldState {
                hold_id,
                state,
                command,
            } => {
                write!(
                    f,
                    "invalid hold state for {}: {:?} cannot {}",
                    hold_id, state, command
                )
            }
            DomainError::HoldExpiredBeforeRequest {
                requested_at,
                expires_at,
            } => {
                write!(
                    f,
                    "hold expired before request: requested_at={} expires_at={}",
                    requested_at, expires_at
                )
            }
            DomainError::HoldNotExpired {
                hold_id,
                now,
                expires_at,
            } => {
                write!(
                    f,
                    "hold {} not expired: now={} expires_at={}",
                    hold_id, now, expires_at
                )
            }
        }
    }
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
    pub fn restore_state(
        &mut self,
        state: CapacityHoldState,
        confirmed_at: Option<u64>,
        released_at: Option<u64>,
        expired_at: Option<u64>,
    ) {
        self.state = state;
        self.confirmed_at = confirmed_at;
        self.released_at = released_at;
        self.expired_at = expired_at;
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
pub struct EventEnvelope {
    pub event_id: String,
    pub event_type: String,
    pub schema_version: u32,
    pub occurred_at: u64,
    pub correlation_id: String,
    pub causation_id: Option<String>,
    pub producer: String,
}

impl EventEnvelope {
    pub fn new(
        event_type: impl Into<String>,
        occurred_at: u64,
        correlation_id: impl Into<String>,
        causation_id: Option<impl Into<String>>,
        producer: impl Into<String>,
    ) -> Self {
        Self {
            event_id: format!("evt-{:x}", occurred_at),
            event_type: event_type.into(),
            schema_version: 1,
            occurred_at,
            correlation_id: correlation_id.into(),
            causation_id: causation_id.map(|v| v.into()),
            producer: producer.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum DomainEvent {
    CapacityHeld(CapacityHeld),
    CapacityHoldConfirmed(CapacityHoldConfirmed),
    CapacityReleased(CapacityReleased),
    CapacityHoldExpired(CapacityHoldExpired),
    CapacityHoldFailed(CapacityHoldFailed),
    CapacitySnapshotUpdated(CapacitySnapshotUpdated),
    OverbookingThresholdReached(OverbookingThresholdReached),
    WaitlistActivated(WaitlistActivated),
    WaitlistCapacityFreed(WaitlistCapacityFreed),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CapacityHeld {
    pub envelope: EventEnvelope,
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
    pub envelope: EventEnvelope,
    pub hold_id: HoldId,
    pub inventory_pool_id: InventoryPoolId,
    pub capacity_unit_ref: CapacityUnitRef,
    pub interval: StationInterval,
    pub confirmed_at: u64,
    pub references: ReferenceMetadata,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CapacityReleased {
    pub envelope: EventEnvelope,
    pub hold_id: HoldId,
    pub inventory_pool_id: InventoryPoolId,
    pub capacity_unit_ref: CapacityUnitRef,
    /// Service Plan segment this pool sells; lets downstream consumers
    /// (waitlist) match released capacity without a pool lookup.
    pub service_segment_ref: String,
    pub interval: StationInterval,
    pub released_at: u64,
    pub release_reason: String,
    pub references: ReferenceMetadata,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CapacityHoldExpired {
    pub envelope: EventEnvelope,
    pub hold_id: HoldId,
    pub inventory_pool_id: InventoryPoolId,
    pub capacity_unit_ref: CapacityUnitRef,
    pub interval: StationInterval,
    pub expired_at: u64,
    pub references: ReferenceMetadata,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CapacityHoldFailed {
    pub envelope: EventEnvelope,
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
    NoAvailableUnits,
}

impl HoldFailureReason {
    pub fn contract_reason(&self) -> &'static str {
        match self {
            HoldFailureReason::UnknownCapacityUnit => "UNKNOWN_CAPACITY_UNIT",
            HoldFailureReason::IdempotencyConflict { .. } => "IDEMPOTENCY_CONFLICT",
            HoldFailureReason::OverlappingHold { .. } => "OVERLAPPING_HOLD",
            HoldFailureReason::NoAvailableUnits => "NO_AVAILABLE_CAPACITY",
        }
    }

    pub fn conflicting_hold_id(&self) -> Option<&HoldId> {
        match self {
            HoldFailureReason::OverlappingHold {
                conflicting_hold_id,
            } => Some(conflicting_hold_id),
            HoldFailureReason::UnknownCapacityUnit
            | HoldFailureReason::IdempotencyConflict { .. }
            | HoldFailureReason::NoAvailableUnits => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OverbookingPolicy {
    pub max_overbooking_pct: f64,
    pub no_show_rate: f64,
    pub safety_margin_pct: f64,
}

impl OverbookingPolicy {
    pub fn none() -> Self {
        Self {
            max_overbooking_pct: 0.0,
            no_show_rate: 0.0,
            safety_margin_pct: 0.0,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WaitlistState {
    Inactive,
    Active { queue_size: u32 },
}

impl WaitlistState {
    fn queue_size(&self) -> u32 {
        match self {
            WaitlistState::Inactive => 0,
            WaitlistState::Active { queue_size } => *queue_size,
        }
    }

    fn activate_next(&mut self) -> u32 {
        let next = self.queue_size().saturating_add(1);
        *self = WaitlistState::Active { queue_size: next };
        next
    }

    fn is_active(&self) -> bool {
        matches!(self, WaitlistState::Active { .. })
    }
}

#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClassCapacity {
    pub class_ref: String,
    pub physical_capacity: u32,
    pub effective_capacity: u32,
    pub hold_count: u32,
    pub confirmed_count: u32,
    pub remaining_capacity: u32,
    pub overbooking_policy: OverbookingPolicy,
}

#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CapacitySnapshot {
    pub segment_ref: String,
    pub departure_date: String,
    pub total_capacity: u32,
    pub remaining_capacity: u32,
    pub physical_capacity: u32,
    pub hold_count: u32,
    pub confirmed_count: u32,
    pub utilization_pct: f64,
    pub snapshot_version: String,
    pub classes: Vec<ClassCapacity>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CapacitySnapshotUpdated {
    pub envelope: EventEnvelope,
    pub snapshot: CapacitySnapshot,
}

#[derive(Debug, Clone, PartialEq)]
pub struct OverbookingThresholdReached {
    pub envelope: EventEnvelope,
    pub pool_id: InventoryPoolId,
    pub physical_capacity: u32,
    pub confirmed_count: u32,
    pub overbooking_pct: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct WaitlistActivated {
    pub envelope: EventEnvelope,
    pub segment_ref: String,
    pub departure_date: String,
    pub queue_position: u32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct WaitlistCapacityFreed {
    pub envelope: EventEnvelope,
    pub segment_ref: String,
    pub departure_date: String,
    pub freed_slots: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InventoryPoolState {
    Initialized,
    OpenForSale,
    Frozen,
    ClosedForSale,
    Cancelled,
}

#[derive(Debug, Clone, PartialEq)]
pub struct InventoryPool {
    pub identity: InventoryPoolIdentity,
    pub(crate) capacity_units: HashSet<CapacityUnitRef>,
    holds: HashMap<HoldId, CapacityHold>,
    idempotency_index: HashMap<IdempotencyKey, HoldId>,
    version: u64,
    state: InventoryPoolState,
    overbooking_policy: OverbookingPolicy,
    waitlist_state: WaitlistState,
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
            overbooking_policy: OverbookingPolicy::none(),
            waitlist_state: WaitlistState::Inactive,
        })
    }

    pub fn version(&self) -> u64 {
        self.version
    }

    pub fn state(&self) -> InventoryPoolState {
        self.state
    }

    pub fn with_overbooking_policy(mut self, policy: OverbookingPolicy) -> Self {
        self.overbooking_policy = policy;
        self
    }

    pub fn set_overbooking_policy(&mut self, policy: OverbookingPolicy) {
        self.overbooking_policy = policy;
        self.version += 1;
    }

    pub fn overbooking_policy(&self) -> OverbookingPolicy {
        self.overbooking_policy
    }

    pub fn physical_capacity(&self) -> u32 {
        self.capacity_units.len() as u32
    }

    pub fn effective_capacity(&self) -> u32 {
        let physical = self.physical_capacity() as f64;
        (physical * (1.0 + self.overbooking_policy.max_overbooking_pct.max(0.0) / 100.0)).floor()
            as u32
    }

    pub fn waitlist_state(&self) -> &WaitlistState {
        &self.waitlist_state
    }

    pub fn total_units(&self) -> usize {
        self.capacity_units.len()
    }

    pub fn hold(&self, hold_id: &HoldId) -> Option<&CapacityHold> {
        self.holds.get(hold_id)
    }

    pub fn capacity_unit_refs(&self) -> Vec<CapacityUnitRef> {
        self.capacity_units.iter().cloned().collect()
    }

    pub fn holds(&self) -> Vec<&CapacityHold> {
        self.holds.values().collect()
    }

    pub fn restore_hold(&mut self, hold: CapacityHold) -> DomainResult<()> {
        self.ensure_unit_known(&hold.scope.capacity_unit_ref)?;
        self.idempotency_index
            .insert(hold.idempotency_key.clone(), hold.hold_id.clone());
        self.holds.insert(hold.hold_id.clone(), hold);
        Ok(())
    }

    pub fn request_hold(
        &mut self,
        mut hold: CapacityHold,
        now: u64,
    ) -> DomainResult<Vec<DomainEvent>> {
        self.ensure_unit_known(&hold.scope.capacity_unit_ref)?;

        if let Some(existing_hold_id) = self.idempotency_index.get(&hold.idempotency_key) {
            let existing = self
                .holds
                .get(existing_hold_id)
                .expect("idempotency index references an existing hold");
            if same_hold_request(existing, &hold) {
                return Ok(vec![DomainEvent::CapacityHeld(
                    existing.to_capacity_held(true),
                )]);
            }
            return Err(DomainError::IdempotencyConflict {
                idempotency_key: hold.idempotency_key.to_string(),
                existing_hold_id: existing_hold_id.to_string(),
            });
        }

        let occupied = self.occupied_count_at(&hold.scope.station_interval, now);
        if occupied >= self.effective_capacity() {
            hold.state = CapacityHoldState::Failed;
            let queue_position = self.waitlist_state.activate_next();
            self.holds.insert(hold.hold_id.clone(), hold);
            self.version += 1;
            return Ok(vec![
                self.waitlist_activated_event(now, queue_position),
                self.snapshot_updated_event(now),
            ]);
        }

        if occupied < self.physical_capacity() {
            if let Some(conflict) = self.conflicting_hold(
                &hold.hold_id,
                &hold.scope.capacity_unit_ref,
                &hold.scope.station_interval,
                now,
            ) {
                let event = CapacityHoldFailed {
                    envelope: EventEnvelope::new(
                        "CapacityHoldFailed",
                        now,
                        &hold.scope.references.source_context,
                        None::<String>,
                        "capacity-availability",
                    ),
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
                return Ok(vec![
                    DomainEvent::CapacityHoldFailed(event),
                    self.snapshot_updated_event(now),
                ]);
            }
        }

        hold.mark_held();
        let event = DomainEvent::CapacityHeld(hold.to_capacity_held(false));
        self.idempotency_index
            .insert(hold.idempotency_key.clone(), hold.hold_id.clone());
        self.holds.insert(hold.hold_id.clone(), hold);
        self.version += 1;
        Ok(vec![event, self.snapshot_updated_event(now)])
    }

    pub fn confirm_hold(&mut self, hold_id: &HoldId, now: u64) -> DomainResult<Vec<DomainEvent>> {
        let event = {
            let hold = self
                .holds
                .get_mut(hold_id)
                .ok_or_else(|| DomainError::UnknownHold(hold_id.to_string()))?;
            hold.confirm(now)?;
            DomainEvent::CapacityHoldConfirmed(CapacityHoldConfirmed {
                envelope: EventEnvelope::new(
                    "CapacityHoldConfirmed",
                    now,
                    &hold.scope.references.source_context,
                    None::<String>,
                    "capacity-availability",
                ),
                hold_id: hold.hold_id.clone(),
                inventory_pool_id: hold.scope.inventory_pool_id.clone(),
                capacity_unit_ref: hold.scope.capacity_unit_ref.clone(),
                interval: hold.scope.station_interval.clone(),
                confirmed_at: now,
                references: hold.scope.references.clone(),
            })
        };
        self.version += 1;
        let mut events = vec![event, self.snapshot_updated_event(now)];
        if self.confirmed_count() > self.physical_capacity() {
            events.push(DomainEvent::OverbookingThresholdReached(
                OverbookingThresholdReached {
                    envelope: EventEnvelope::new(
                        "OverbookingThresholdReached",
                        now,
                        &self.identity.service_segment_ref,
                        None::<String>,
                        "capacity-availability",
                    ),
                    pool_id: self.identity.pool_id.clone(),
                    physical_capacity: self.physical_capacity(),
                    confirmed_count: self.confirmed_count(),
                    overbooking_pct: self.overbooking_policy.max_overbooking_pct,
                },
            ));
        }
        Ok(events)
    }

    pub fn release_hold(
        &mut self,
        hold_id: &HoldId,
        now: u64,
        release_reason: impl Into<String>,
    ) -> DomainResult<Vec<DomainEvent>> {
        let release_reason = non_empty(release_reason, "release_reason")?;
        let before_remaining = self.remaining_capacity();
        let event = {
            let hold = self
                .holds
                .get_mut(hold_id)
                .ok_or_else(|| DomainError::UnknownHold(hold_id.to_string()))?;
            hold.release(now)?;
            DomainEvent::CapacityReleased(CapacityReleased {
                envelope: EventEnvelope::new(
                    "CapacityReleased",
                    now,
                    &hold.scope.references.source_context,
                    None::<String>,
                    "capacity-availability",
                ),
                hold_id: hold.hold_id.clone(),
                inventory_pool_id: hold.scope.inventory_pool_id.clone(),
                capacity_unit_ref: hold.scope.capacity_unit_ref.clone(),
                service_segment_ref: self.identity.service_segment_ref.clone(),
                interval: hold.scope.station_interval.clone(),
                released_at: now,
                release_reason,
                references: hold.scope.references.clone(),
            })
        };
        self.version += 1;
        let mut events = vec![event, self.snapshot_updated_event(now)];
        let after_remaining = self.remaining_capacity();
        if self.waitlist_state.is_active() && after_remaining > before_remaining {
            events
                .push(self.waitlist_capacity_freed_event(now, after_remaining - before_remaining));
        }
        Ok(events)
    }

    pub fn expire_hold(&mut self, hold_id: &HoldId, now: u64) -> DomainResult<Vec<DomainEvent>> {
        let before_remaining = self.remaining_capacity();
        let event = {
            let hold = self
                .holds
                .get_mut(hold_id)
                .ok_or_else(|| DomainError::UnknownHold(hold_id.to_string()))?;
            hold.expire(now)?;
            DomainEvent::CapacityHoldExpired(CapacityHoldExpired {
                envelope: EventEnvelope::new(
                    "CapacityHoldExpired",
                    now,
                    &hold.scope.references.source_context,
                    None::<String>,
                    "capacity-availability",
                ),
                hold_id: hold.hold_id.clone(),
                inventory_pool_id: hold.scope.inventory_pool_id.clone(),
                capacity_unit_ref: hold.scope.capacity_unit_ref.clone(),
                interval: hold.scope.station_interval.clone(),
                expired_at: now,
                references: hold.scope.references.clone(),
            })
        };
        self.version += 1;
        let mut events = vec![event, self.snapshot_updated_event(now)];
        let after_remaining = self.remaining_capacity();
        if self.waitlist_state.is_active() && after_remaining > before_remaining {
            events
                .push(self.waitlist_capacity_freed_event(now, after_remaining - before_remaining));
        }
        Ok(events)
    }

    pub fn availability_snapshot(
        &self,
        snapshot_id: impl Into<String>,
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
        let sellable = available_count > 0;
        let status = if total_units == 0 || available_count == 0 {
            AvailabilityStatus::Unavailable
        } else if available_count <= 2 || available_count * 4 <= total_units {
            AvailabilityStatus::Limited
        } else {
            AvailabilityStatus::Available
        };
        let explanations = match status {
            AvailabilityStatus::Available => vec![AvailabilityExplanation::InventoryAvailable],
            AvailabilityStatus::Limited => vec![AvailabilityExplanation::LowAvailability],
            AvailabilityStatus::Unavailable => vec![AvailabilityExplanation::NoUnitsAvailable],
            AvailabilityStatus::Unknown => vec![],
        };

        AvailabilitySnapshot {
            snapshot_id: snapshot_id.into(),
            snapshot_version: self.version,
            inventory_pool_id: self.identity.pool_id.clone(),
            requested_interval,
            source_version: self.version,
            generated_at,
            valid_until,
            sellable,
            total_units,
            available_count,
            available_units,
            status,
            explanations,
        }
    }

    pub fn snapshot(&self) -> CapacitySnapshot {
        let physical_capacity = self.physical_capacity();
        let total_capacity = self.effective_capacity();
        let hold_count = self.held_count();
        let confirmed_count = self.confirmed_count();
        let remaining_capacity = total_capacity.saturating_sub(hold_count + confirmed_count);
        let utilization_pct = if total_capacity == 0 {
            0.0
        } else {
            ((hold_count + confirmed_count) as f64 / total_capacity as f64) * 100.0
        };
        let class = ClassCapacity {
            class_ref: self.identity.seat_class_or_cabin_ref.clone(),
            physical_capacity,
            effective_capacity: total_capacity,
            hold_count,
            confirmed_count,
            remaining_capacity,
            overbooking_policy: self.overbooking_policy,
        };

        CapacitySnapshot {
            segment_ref: self.identity.service_segment_ref.clone(),
            departure_date: self.identity.service_date.clone(),
            total_capacity,
            remaining_capacity,
            physical_capacity,
            hold_count,
            confirmed_count,
            utilization_pct,
            snapshot_version: self.version.to_string(),
            classes: vec![class],
        }
    }

    pub fn remaining_capacity(&self) -> u32 {
        self.effective_capacity()
            .saturating_sub(self.held_count() + self.confirmed_count())
    }

    fn held_count(&self) -> u32 {
        self.holds
            .values()
            .filter(|hold| matches!(hold.state, CapacityHoldState::Held))
            .count() as u32
    }

    fn confirmed_count(&self) -> u32 {
        self.holds
            .values()
            .filter(|hold| matches!(hold.state, CapacityHoldState::Confirmed))
            .count() as u32
    }

    fn occupied_count_at(&self, interval: &StationInterval, now: u64) -> u32 {
        self.holds
            .values()
            .filter(|hold| {
                hold.is_blocking_at(now) && hold.scope.station_interval.overlaps(interval)
            })
            .count() as u32
    }

    fn snapshot_updated_event(&self, now: u64) -> DomainEvent {
        DomainEvent::CapacitySnapshotUpdated(CapacitySnapshotUpdated {
            envelope: EventEnvelope::new(
                "CapacitySnapshotUpdated",
                now,
                &self.identity.service_segment_ref,
                None::<String>,
                "capacity-availability",
            ),
            snapshot: self.snapshot(),
        })
    }

    fn waitlist_activated_event(&self, now: u64, queue_position: u32) -> DomainEvent {
        DomainEvent::WaitlistActivated(WaitlistActivated {
            envelope: EventEnvelope::new(
                "WaitlistActivated",
                now,
                &self.identity.service_segment_ref,
                None::<String>,
                "capacity-availability",
            ),
            segment_ref: self.identity.service_segment_ref.clone(),
            departure_date: self.identity.service_date.clone(),
            queue_position,
        })
    }

    fn waitlist_capacity_freed_event(&self, now: u64, freed_slots: u32) -> DomainEvent {
        DomainEvent::WaitlistCapacityFreed(WaitlistCapacityFreed {
            envelope: EventEnvelope::new(
                "WaitlistCapacityFreed",
                now,
                &self.identity.service_segment_ref,
                None::<String>,
                "capacity-availability",
            ),
            segment_ref: self.identity.service_segment_ref.clone(),
            departure_date: self.identity.service_date.clone(),
            freed_slots,
        })
    }

    fn ensure_unit_known(&self, unit: &CapacityUnitRef) -> DomainResult<()> {
        if self.capacity_units.contains(unit) {
            Ok(())
        } else {
            Err(DomainError::UnknownCapacityUnit(unit.to_string()))
        }
    }

    /// First unit (stable seat order) with no blocking hold overlapping the
    /// interval. Used by hold placement so new holds do not pile onto an
    /// already-held unit.
    pub fn find_available_unit(
        &self,
        interval: &StationInterval,
        now: u64,
    ) -> Option<CapacityUnitRef> {
        let mut units: Vec<&CapacityUnitRef> = self.capacity_units.iter().collect();
        units.sort_by(|a, b| a.as_str().cmp(b.as_str()));
        units
            .into_iter()
            .find(|unit| {
                !self.holds.values().any(|existing| {
                    existing.scope.capacity_unit_ref == **unit
                        && existing.is_blocking_at(now)
                        && existing.scope.station_interval.overlaps(interval)
                })
            })
            .cloned()
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
            envelope: EventEnvelope::new(
                "CapacityHeld",
                self.requested_at,
                &self.scope.references.source_context,
                None::<String>,
                "capacity-availability",
            ),
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
    pub snapshot_id: String,
    pub snapshot_version: u64,
    pub inventory_pool_id: InventoryPoolId,
    pub requested_interval: StationInterval,
    pub source_version: u64,
    pub generated_at: u64,
    pub valid_until: u64,
    pub sellable: bool,
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
    Limited,
    Unknown,
    Unavailable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AvailabilityExplanation {
    InventoryAvailable,
    LowAvailability,
    NoUnitsAvailable,
}

// ---------------------------------------------------------------------------
// Timestamp conversion helpers -- UnixMillis <-> RFC3339 UTC
// ---------------------------------------------------------------------------

/// Convert Unix millisecond timestamp to RFC3339 UTC string.
pub fn unix_millis_to_rfc3339(ms: u64) -> String {
    let secs = ms / 1000;
    let subsec_ms = ms % 1000;
    let (year, month, day, hour, min, sec) = epoch_seconds_to_ymdhms(secs);
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}.{:03}Z",
        year, month, day, hour, min, sec, subsec_ms
    )
}

/// Parse RFC3339 UTC string to Unix millisecond timestamp.
/// Supports formats: "2026-07-04T10:00:00Z", "2026-07-04T10:00:00.123Z"
pub fn rfc3339_to_unix_millis(s: &str) -> Option<u64> {
    let s = s.strip_suffix('Z')?;
    let (date_part, time_part) = s.split_once('T')?;
    let parts: Vec<&str> = date_part.split('-').collect();
    if parts.len() != 3 {
        return None;
    }
    let year: u64 = parts[0].parse().ok()?;
    let month: u64 = parts[1].parse().ok()?;
    let day: u64 = parts[2].parse().ok()?;

    let (hms, millis) = if let Some((hms, ms_str)) = time_part.split_once('.') {
        let ms: u64 = ms_str.parse().ok()?;
        (hms, ms)
    } else {
        (time_part, 0)
    };

    let time_parts: Vec<&str> = hms.split(':').collect();
    if time_parts.len() != 3 {
        return None;
    }
    let hour: u64 = time_parts[0].parse().ok()?;
    let min: u64 = time_parts[1].parse().ok()?;
    let sec: u64 = time_parts[2].parse().ok()?;

    let total_days = days_since_epoch(year, month, day)?;
    let total_secs = total_days * 86400 + hour * 3600 + min * 60 + sec;
    Some(total_secs * 1000 + millis)
}

fn epoch_seconds_to_ymdhms(secs: u64) -> (u64, u32, u32, u32, u32, u32) {
    let days = secs / 86400;
    let time_secs = secs % 86400;
    let h = (time_secs / 3600) as u32;
    let m = ((time_secs % 3600) / 60) as u32;
    let s = (time_secs % 60) as u32;
    let mut y = 1970i64;
    let mut d = days as i64;
    loop {
        let days_in_year = if is_leap_year(y as u64) { 366 } else { 365 };
        if d < days_in_year {
            break;
        }
        d -= days_in_year;
        y += 1;
    }
    let leap = is_leap_year(y as u64);
    const MONTH_DAYS: [i64; 12] = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    let mut mo = 1u32;
    for &md in MONTH_DAYS.iter() {
        let md_adj = if mo == 2 && leap { md + 1 } else { md };
        if d < md_adj {
            break;
        }
        d -= md_adj;
        mo += 1;
    }
    (y as u64, mo, (d + 1) as u32, h, m, s)
}

fn is_leap_year(y: u64) -> bool {
    (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
}

fn days_since_epoch(year: u64, month: u64, day: u64) -> Option<u64> {
    if month < 1 || month > 12 || day < 1 || day > 31 {
        return None;
    }
    // Days from 1970-01-01 using a simple cumulative algorithm
    let mut y = 1970i64;
    let target_y = year as i64;
    let target_m = month as i64;
    let target_d = day as i64;
    let mut total_days: i64 = 0;

    // Add days for whole years
    while y < target_y {
        total_days += if is_leap_year(y as u64) { 366 } else { 365 };
        y += 1;
    }

    // Add days for months in target year
    const MONTH_DAYS: [i64; 12] = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    for m in 1..target_m {
        let mut md = MONTH_DAYS[(m - 1) as usize];
        if m == 2 && is_leap_year(target_y as u64) {
            md = 29;
        }
        total_days += md;
    }

    // Add days in current month (1-based)
    total_days += target_d - 1;

    Some(total_days as u64)
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
        let events = pool.request_hold(first, 10).unwrap();
        assert!(matches!(events.first(), Some(DomainEvent::CapacityHeld(_))));

        let overlap = request("hold-2", "01A", 2, 4, "idem-2", 11, 71);
        let failed = pool.request_hold(overlap, 11).unwrap();
        match failed.first() {
            Some(DomainEvent::CapacityHoldFailed(event)) => assert!(matches!(
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
        assert!(matches!(
            confirmed.first(),
            Some(DomainEvent::CapacityHoldConfirmed(_))
        ));
        assert!(pool.expire_hold(&hold_id("hold-1"), 80).is_err());
        let released = pool
            .release_hold(&hold_id("hold-1"), 90, "post-sales-void")
            .unwrap();
        assert!(matches!(
            released.first(),
            Some(DomainEvent::CapacityReleased(_))
        ));

        pool.request_hold(request("hold-2", "01A", 1, 3, "idem-2", 100, 110), 100)
            .unwrap();
        assert!(pool.expire_hold(&hold_id("hold-2"), 109).is_err());
        let expired = pool.expire_hold(&hold_id("hold-2"), 110).unwrap();
        assert!(matches!(
            expired.first(),
            Some(DomainEvent::CapacityHoldExpired(_))
        ));
    }

    #[test]
    fn request_hold_is_idempotent_for_identical_command() {
        let mut pool = pool();
        let first = request("hold-1", "01A", 1, 3, "idem-1", 10, 70);
        pool.request_hold(first.clone(), 10).unwrap();
        let replay = pool.request_hold(first, 11).unwrap();
        match replay.first() {
            Some(DomainEvent::CapacityHeld(event)) => assert!(event.idempotent_replay),
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

        let snapshot =
            pool.availability_snapshot("avs-test-1", StationInterval::new(2, 3).unwrap(), 20, 30);
        assert_eq!(snapshot.total_units, 2);
        assert_eq!(snapshot.available_count, 1);
        assert_eq!(snapshot.available_units, vec![unit("01B")]);
        assert_eq!(snapshot.status, AvailabilityStatus::Limited);
        assert_eq!(pool.holds.len(), 1, "snapshot must not create a hold");

        let after_expiry =
            pool.availability_snapshot("avs-test-2", StationInterval::new(2, 3).unwrap(), 70, 80);
        assert_eq!(after_expiry.available_count, 2);
    }

    fn large_pool(size: u32, overbooking_pct: f64) -> InventoryPool {
        let units = (0..size)
            .map(|i| unit(&format!("{:03}", i)))
            .collect::<Vec<_>>();
        InventoryPool::new(identity(), units)
            .unwrap()
            .with_overbooking_policy(OverbookingPolicy {
                max_overbooking_pct: overbooking_pct,
                no_show_rate: 3.0,
                safety_margin_pct: 1.0,
            })
    }

    #[test]
    fn overbooking_limit_accepts_105th_and_waitlists_106th_for_100_seats() {
        let mut pool = large_pool(100, 5.0);
        for i in 0..105 {
            let seat = format!("{:03}", i.min(99));
            let events = pool
                .request_hold(
                    request(
                        &format!("hold-{i}"),
                        &seat,
                        1,
                        2,
                        &format!("idem-{i}"),
                        10 + i as u64,
                        1_000 + i as u64,
                    ),
                    10 + i as u64,
                )
                .unwrap();
            assert!(matches!(events[0], DomainEvent::CapacityHeld(_)));
        }
        assert_eq!(pool.snapshot().remaining_capacity, 0);

        let waitlisted = pool
            .request_hold(
                request("hold-106", "099", 1, 2, "idem-106", 200, 1_200),
                200,
            )
            .unwrap();
        assert!(
            waitlisted
                .iter()
                .any(|event| matches!(event, DomainEvent::WaitlistActivated(_)))
        );
        assert!(matches!(
            pool.waitlist_state(),
            WaitlistState::Active { queue_size: 1 }
        ));
    }

    #[test]
    fn snapshot_updated_emitted_with_correct_counts_on_mutations() {
        let mut pool = large_pool(2, 0.0);
        let events = pool
            .request_hold(request("hold-snap", "000", 1, 2, "idem-snap", 10, 20), 10)
            .unwrap();
        let snapshot = events
            .iter()
            .find_map(|event| match event {
                DomainEvent::CapacitySnapshotUpdated(event) => Some(&event.snapshot),
                _ => None,
            })
            .expect("snapshot event");
        assert_eq!(snapshot.total_capacity, 2);
        assert_eq!(snapshot.remaining_capacity, 1);
        assert_eq!(snapshot.hold_count, 1);
        assert_eq!(snapshot.confirmed_count, 0);

        let events = pool.confirm_hold(&hold_id("hold-snap"), 11).unwrap();
        let snapshot = events
            .iter()
            .find_map(|event| match event {
                DomainEvent::CapacitySnapshotUpdated(event) => Some(&event.snapshot),
                _ => None,
            })
            .expect("snapshot event");
        assert_eq!(snapshot.remaining_capacity, 1);
        assert_eq!(snapshot.hold_count, 0);
        assert_eq!(snapshot.confirmed_count, 1);

        let events = pool
            .release_hold(&hold_id("hold-snap"), 12, "test-release")
            .unwrap();
        let snapshot = events
            .iter()
            .find_map(|event| match event {
                DomainEvent::CapacitySnapshotUpdated(event) => Some(&event.snapshot),
                _ => None,
            })
            .expect("snapshot event");
        assert_eq!(snapshot.remaining_capacity, 2);
        assert_eq!(snapshot.hold_count, 0);
        assert_eq!(snapshot.confirmed_count, 0);
    }

    #[test]
    fn overbooking_threshold_and_waitlist_freed_are_emitted() {
        let mut pool = large_pool(1, 100.0);
        pool.request_hold(request("hold-a", "000", 1, 2, "idem-a", 10, 50), 10)
            .unwrap();
        pool.request_hold(request("hold-b", "000", 1, 2, "idem-b", 11, 51), 11)
            .unwrap();
        pool.request_hold(request("hold-c", "000", 1, 2, "idem-c", 12, 52), 12)
            .unwrap();

        pool.confirm_hold(&hold_id("hold-a"), 20).unwrap();
        let events = pool.confirm_hold(&hold_id("hold-b"), 21).unwrap();
        assert!(
            events
                .iter()
                .any(|event| matches!(event, DomainEvent::OverbookingThresholdReached(_)))
        );
        let events = pool
            .release_hold(&hold_id("hold-a"), 30, "test-release")
            .unwrap();
        assert!(
            events
                .iter()
                .any(|event| matches!(event, DomainEvent::WaitlistCapacityFreed(_)))
        );
    }
    #[test]
    fn unix_millis_to_rfc3339_round_trip() {
        // Known timestamp: 2027-01-01T00:00:00.000Z
        let ms: u64 = 1800000000000;
        let rfc = unix_millis_to_rfc3339(ms);
        assert!(rfc.ends_with('Z'), "RFC3339 should end with Z: {}", rfc);
        assert!(
            rfc.contains("2027-01-") || rfc.contains("2026-"),
            "unexpected year in {}",
            rfc
        );

        // Round-trip
        let parsed = rfc3339_to_unix_millis(&rfc);
        assert_eq!(
            parsed,
            Some(ms),
            "round-trip failed: {} -> {} -> {:?}",
            ms,
            rfc,
            parsed
        );
    }

    #[test]
    fn rfc3339_to_unix_millis_parses_valid() {
        let cases = vec![
            ("2026-07-04T10:00:00Z", Some(1783159200000)),
            ("2026-07-04T10:00:00.000Z", Some(1783159200000)),
            ("2027-01-01T00:00:00Z", Some(1798761600000)),
        ];
        for (input, expected) in cases {
            let result = rfc3339_to_unix_millis(input);
            assert_eq!(result, expected, "failed to parse {}", input);
        }
    }

    #[test]
    fn rfc3339_to_unix_millis_rejects_invalid() {
        assert_eq!(rfc3339_to_unix_millis("not-a-date"), None);
        assert_eq!(rfc3339_to_unix_millis("2026-13-01T00:00:00Z"), None);
    }
}
