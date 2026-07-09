use crate::utils::*;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SeatMapStatus {
    Draft,
    Validating,
    Published,
    Superseded,
    Retired,
    Failed,
}
impl SeatMapStatus {
    pub fn as_contract(self) -> &'static str {
        match self {
            Self::Draft => "DRAFT",
            Self::Validating => "VALIDATING",
            Self::Published => "PUBLISHED",
            Self::Superseded => "SUPERSEDED",
            Self::Retired => "RETIRED",
            Self::Failed => "FAILED",
        }
    }
}
impl fmt::Display for SeatMapStatus {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_contract())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AllocationStatus {
    Requested,
    Allocated,
    Standing,
    Confirmed,
    Released,
    Expired,
    Failed,
    Missed,
}
impl AllocationStatus {
    pub fn as_contract(self) -> &'static str {
        match self {
            Self::Requested => "REQUESTED",
            Self::Allocated => "ALLOCATED",
            Self::Standing => "STANDING",
            Self::Confirmed => "CONFIRMED",
            Self::Released => "RELEASED",
            Self::Expired => "EXPIRED",
            Self::Failed => "FAILED",
            Self::Missed => "MISSED",
        }
    }
    pub fn is_active(self) -> bool {
        matches!(self, Self::Allocated | Self::Standing | Self::Confirmed)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AllocationType {
    Seat,
    Berth,
    Standing,
}
impl AllocationType {
    pub fn as_contract(self) -> &'static str {
        match self {
            Self::Seat => "SEAT",
            Self::Berth => "BERTH",
            Self::Standing => "STANDING",
        }
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum BerthPosition {
    Upper,
    Middle,
    Lower,
    SideUpper,
    SideLower,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SeatPosition {
    Window,
    Aisle,
    Middle,
    LowerDeck,
    UpperDeck,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AdjacencyPreference {
    None,
    SameCoach,
    SameRow,
    Adjacent,
    SameCompartment,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DegradationReason {
    None,
    NoAdjacentBlock,
    ClassMismatch,
    IntervalConflict,
    BerthPreferenceUnavailable,
    StandingAssigned,
    PolicyLimit,
}
impl DegradationReason {
    pub fn as_contract(self) -> &'static str {
        match self {
            Self::None => "NONE",
            Self::NoAdjacentBlock => "NO_ADJACENT_BLOCK",
            Self::ClassMismatch => "CLASS_MISMATCH",
            Self::IntervalConflict => "INTERVAL_CONFLICT",
            Self::BerthPreferenceUnavailable => "BERTH_PREFERENCE_UNAVAILABLE",
            Self::StandingAssigned => "STANDING_ASSIGNED",
            Self::PolicyLimit => "POLICY_LIMIT",
        }
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ReleaseReason {
    Voided,
    Changed,
    IssueFailed,
    CapacityReleased,
    HoldExpired,
    Disruption,
    ManualCorrection,
}
impl ReleaseReason {
    pub fn as_contract(self) -> &'static str {
        match self {
            Self::Voided => "VOIDED",
            Self::Changed => "CHANGED",
            Self::IssueFailed => "ISSUE_FAILED",
            Self::CapacityReleased => "CAPACITY_RELEASED",
            Self::HoldExpired => "HOLD_EXPIRED",
            Self::Disruption => "DISRUPTION",
            Self::ManualCorrection => "MANUAL_CORRECTION",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SeatUnit {
    pub seat_unit_ref: String,
    pub coach_ref: String,
    pub coach_no: String,
    pub seat_no: String,
    pub allocation_type: AllocationType,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub berth_position: Option<BerthPosition>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seat_position: Option<SeatPosition>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub row_no: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub adjacency_group_key: Option<String>,
    pub assignable: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub unavailable_reason: Option<String>,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Coach {
    pub coach_ref: String,
    pub coach_no: String,
    pub class_ref: String,
    pub coach_type: String,
    pub assignable: bool,
    pub seat_units: Vec<SeatUnit>,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SeatMap {
    pub seat_map_id: String,
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub composition_version: String,
    pub seat_map_version: i64,
    pub source: String,
    pub composition_seed: String,
    pub mapping_version: String,
    pub status: SeatMapStatus,
    pub coaches: Vec<Coach>,
    pub created_at: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub published_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub retired_at: Option<String>,
}

impl SeatMap {
    pub fn build(
        command: CreateSeatMapCommand,
        now: String,
    ) -> Result<(Self, Vec<SeatAssignmentEvent>), SeatAssignmentError> {
        validate_prefixed_uuid(&command.scheduled_service_ref, "scheduledServiceRef", "ss-")?;
        validate_date(&command.service_date, "serviceDate")?;
        validate_non_empty(&command.composition_version, "compositionVersion")?;
        validate_non_empty(&command.composition_seed, "compositionSeed")?;
        validate_non_empty(&command.mapping_version, "mappingVersion")?;
        validate_non_empty(&command.operator_ref, "operatorRef")?;
        let seat_map_id = format!("smap-{}", uuid::Uuid::now_v7());
        let coaches = build_sim_topology(
            &seat_map_id,
            &command.composition_seed,
            command.change_scenario.as_deref(),
        );
        let map = Self {
            seat_map_id: seat_map_id.clone(),
            scheduled_service_ref: command.scheduled_service_ref,
            service_date: command.service_date,
            composition_version: command.composition_version,
            seat_map_version: 1,
            source: "SIM_SEED".into(),
            composition_seed: command.composition_seed,
            mapping_version: command.mapping_version,
            status: SeatMapStatus::Draft,
            coaches,
            created_at: now.clone(),
            published_at: None,
            retired_at: None,
        };
        Ok((
            map.clone(),
            vec![SeatAssignmentEvent::seat_map_built(&map, now)],
        ))
    }
    pub fn publish(
        &mut self,
        expected: i64,
        operator: String,
        now: String,
    ) -> Result<SeatAssignmentEvent, SeatAssignmentError> {
        if self.seat_map_version != expected {
            return Err(SeatAssignmentError::PreconditionFailed(
                "SeatMap version did not match expectedSeatMapVersion".into(),
            ));
        }
        if self.status != SeatMapStatus::Draft {
            return Err(SeatAssignmentError::DomainRuleViolation(
                "only DRAFT SeatMap can be published".into(),
            ));
        }
        self.status = SeatMapStatus::Published;
        self.published_at = Some(now.clone());
        self.seat_map_version += 1;
        Ok(SeatAssignmentEvent::seat_map_published(self, operator, now))
    }
    pub fn retire(
        &mut self,
        expected: i64,
        reason: String,
        operator: String,
        now: String,
    ) -> Result<SeatAssignmentEvent, SeatAssignmentError> {
        if self.seat_map_version != expected {
            return Err(SeatAssignmentError::PreconditionFailed(
                "SeatMap version did not match expectedSeatMapVersion".into(),
            ));
        }
        if matches!(self.status, SeatMapStatus::Retired | SeatMapStatus::Failed) {
            return Err(SeatAssignmentError::DomainRuleViolation(
                "terminal SeatMap cannot be retired".into(),
            ));
        }
        self.status = if reason == "SUPERSEDED" {
            SeatMapStatus::Superseded
        } else {
            SeatMapStatus::Retired
        };
        self.retired_at = Some(now.clone());
        self.seat_map_version += 1;
        Ok(SeatAssignmentEvent::seat_map_retired(
            self, reason, operator, now,
        ))
    }
    pub fn mark_unavailable(
        &mut self,
        seat_unit_ref: &str,
        expected: i64,
        reason: String,
        operator: String,
        now: String,
    ) -> Result<SeatAssignmentEvent, SeatAssignmentError> {
        self.require_mutable(expected)?;
        let unit = self.find_unit_mut(seat_unit_ref)?;
        unit.assignable = false;
        unit.unavailable_reason = Some(reason.clone());
        let coach_no = unit.coach_no.clone();
        let seat_no = unit.seat_no.clone();
        self.seat_map_version += 1;
        Ok(SeatAssignmentEvent::seat_unit_unavailable(
            &self.seat_map_id,
            self.seat_map_version,
            seat_unit_ref,
            coach_no,
            seat_no,
            reason,
            operator,
            now,
        ))
    }
    pub fn reopen(
        &mut self,
        seat_unit_ref: &str,
        expected: i64,
        reason: String,
        operator: String,
        now: String,
    ) -> Result<SeatAssignmentEvent, SeatAssignmentError> {
        self.require_mutable(expected)?;
        let unit = self.find_unit_mut(seat_unit_ref)?;
        unit.assignable = true;
        unit.unavailable_reason = None;
        let coach_no = unit.coach_no.clone();
        let seat_no = unit.seat_no.clone();
        self.seat_map_version += 1;
        Ok(SeatAssignmentEvent::seat_unit_reopened(
            &self.seat_map_id,
            self.seat_map_version,
            seat_unit_ref,
            coach_no,
            seat_no,
            reason,
            operator,
            now,
        ))
    }
    fn require_mutable(&self, expected: i64) -> Result<(), SeatAssignmentError> {
        if self.seat_map_version != expected {
            return Err(SeatAssignmentError::PreconditionFailed(
                "SeatMap version did not match expectedSeatMapVersion".into(),
            ));
        }
        if self.status == SeatMapStatus::Published {
            return Err(SeatAssignmentError::DomainRuleViolation(
                "PUBLISHED SeatMap is immutable".into(),
            ));
        }
        if matches!(
            self.status,
            SeatMapStatus::Retired | SeatMapStatus::Superseded | SeatMapStatus::Failed
        ) {
            return Err(SeatAssignmentError::DomainRuleViolation(
                "terminal SeatMap cannot be modified".into(),
            ));
        }
        Ok(())
    }
    fn find_unit_mut(&mut self, ref_id: &str) -> Result<&mut SeatUnit, SeatAssignmentError> {
        for c in &mut self.coaches {
            if let Some(u) = c.seat_units.iter_mut().find(|u| u.seat_unit_ref == ref_id) {
                return Ok(u);
            }
        }
        Err(SeatAssignmentError::NotFound("SeatUnit not found".into()))
    }
    pub fn all_units(&self) -> impl Iterator<Item = &SeatUnit> {
        self.coaches.iter().flat_map(|c| c.seat_units.iter())
    }
}

fn build_sim_topology(seat_map_id: &str, seed: &str, scenario: Option<&str>) -> Vec<Coach> {
    let small = scenario == Some("SMALL") || seed.contains("SMALL");
    let standing_only = scenario == Some("STANDING_ONLY");
    let coach_count = if standing_only {
        1
    } else if small {
        1
    } else {
        2
    };
    let seats_per = if small { 2 } else { 8 };
    (1..=coach_count)
        .map(|c| {
            let coach_ref = format!(
                "coach-{}",
                folded_uuid_v7(&format!("{seat_map_id}:coach:{c}"))
            );
            let coach_no = format!("{:02}", c);
            let seat_units = if standing_only {
                vec![]
            } else {
                (1..=seats_per)
                    .map(|s| {
                        let pos = match s % 3 {
                            0 => SeatPosition::Aisle,
                            1 => SeatPosition::Window,
                            _ => SeatPosition::Middle,
                        };
                        SeatUnit {
                            seat_unit_ref: format!(
                                "su-{}",
                                folded_uuid_v7(&format!("{seat_map_id}:coach:{c}:seat:{s}"))
                            ),
                            coach_ref: coach_ref.clone(),
                            coach_no: coach_no.clone(),
                            seat_no: format!(
                                "{}{}",
                                (s + 1) / 2,
                                if s % 2 == 1 { "A" } else { "B" }
                            ),
                            allocation_type: AllocationType::Seat,
                            berth_position: None,
                            seat_position: Some(pos),
                            row_no: Some(((s + 1) / 2).to_string()),
                            adjacency_group_key: Some(format!("{coach_ref}:row:{}", (s + 1) / 2)),
                            assignable: !(scenario == Some("SEAT_DISABLED") && s == 1),
                            unavailable_reason: if scenario == Some("SEAT_DISABLED") && s == 1 {
                                Some("SIM_SCENARIO".into())
                            } else {
                                None
                            },
                        }
                    })
                    .collect()
            };
            Coach {
                coach_ref,
                coach_no,
                class_ref: "standard".into(),
                coach_type: if standing_only {
                    "STANDING_ONLY".into()
                } else {
                    "SEAT".into()
                },
                assignable: true,
                seat_units,
            }
        })
        .collect()
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StationInterval {
    pub from_seq: i32,
    pub to_seq: i32,
}
impl StationInterval {
    pub fn validate(&self) -> Result<(), SeatAssignmentError> {
        if self.to_seq <= self.from_seq {
            Err(SeatAssignmentError::ValidationFailed(
                "interval.toSeq must be greater than fromSeq".into(),
            ))
        } else {
            Ok(())
        }
    }
    pub fn overlaps(&self, other: &Self) -> bool {
        self.from_seq < other.to_seq && other.from_seq < self.to_seq
    }
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SeatPreferences {
    pub accept_standing: bool,
    #[serde(default)]
    pub adjacency_preference: Option<AdjacencyPreference>,
    #[serde(default)]
    pub adjacency_group_ref: Option<String>,
    #[serde(default)]
    pub preferred_seat_positions: Option<Vec<SeatPosition>>,
    #[serde(default)]
    pub preferred_berth_positions: Option<Vec<BerthPosition>>,
    #[serde(default)]
    pub same_compartment: Option<bool>,
    #[serde(default)]
    pub avoid_seat_unit_refs: Option<Vec<String>>,
    pub preference_version: String,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SeatRef {
    pub seat_allocation_id: String,
    pub allocation_type: AllocationType,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seat_map_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seat_map_version: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seat_unit_ref: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub coach_no: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seat_no: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub berth_position: Option<BerthPosition>,
    pub display_label: String,
    pub degraded: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub degradation_reason: Option<DegradationReason>,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SeatAllocation {
    pub seat_allocation_id: String,
    pub segment_booking_id: String,
    pub journey_order_id: String,
    pub traveler_ref: String,
    pub segment_ref: String,
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub capacity_hold_id: String,
    pub capacity_unit_ref: String,
    pub interval: StationInterval,
    pub seat_ref: SeatRef,
    pub status: AllocationStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub preferences: Option<SeatPreferences>,
    pub created_at: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub confirmed_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub released_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expires_at: Option<String>,
    pub version: i64,
}

impl SeatAllocation {
    pub fn allocate(
        cmd: AllocateSeatCommand,
        map: &SeatMap,
        occupied: &[SeatAllocation],
        now: String,
    ) -> Result<(Self, Vec<SeatAssignmentEvent>), SeatAssignmentError> {
        cmd.interval.validate()?;
        validate_prefixed_uuid(&cmd.segment_booking_id, "segmentBookingId", "sb-")?;
        validate_prefixed_uuid(&cmd.journey_order_id, "journeyOrderId", "ord-")?;
        validate_prefixed_uuid(&cmd.scheduled_service_ref, "scheduledServiceRef", "ss-")?;
        validate_date(&cmd.service_date, "serviceDate")?;
        validate_non_empty(&cmd.capacity_hold_id, "capacityHoldId")?;
        validate_non_empty(&cmd.capacity_unit_ref, "capacityUnitRef")?;
        validate_non_empty(&cmd.class_ref, "classRef")?;
        if map.status != SeatMapStatus::Published
            || map.scheduled_service_ref != cmd.scheduled_service_ref
            || map.service_date != cmd.service_date
        {
            return Err(SeatAssignmentError::PreconditionFailed(
                "no published SeatMap matches the requested scheduled service/date".into(),
            ));
        }
        let allocation_id = format!("salloc-{}", uuid::Uuid::now_v7());
        let prefs = cmd.seat_preferences.clone();
        let avoid: std::collections::HashSet<String> = prefs
            .as_ref()
            .and_then(|p| p.avoid_seat_unit_refs.clone())
            .unwrap_or_default()
            .into_iter()
            .collect();
        let seat_pref = prefs
            .as_ref()
            .and_then(|p| p.preferred_seat_positions.clone())
            .unwrap_or_default();
        let candidates: Vec<&SeatUnit> = map
            .coaches
            .iter()
            .filter(|c| c.assignable && c.class_ref == cmd.class_ref)
            .flat_map(|c| c.seat_units.iter())
            .filter(|u| u.assignable && !avoid.contains(&u.seat_unit_ref))
            .filter(|u| {
                !occupied.iter().any(|a| {
                    a.status.is_active()
                        && a.seat_ref.seat_unit_ref.as_deref() == Some(&u.seat_unit_ref)
                        && a.interval.overlaps(&cmd.interval)
                })
            })
            .collect();
        let chosen = candidates
            .iter()
            .copied()
            .find(|u| {
                seat_pref.is_empty() || u.seat_position.is_some_and(|p| seat_pref.contains(&p))
            })
            .or_else(|| candidates.first().copied());
        if let Some(unit) = chosen {
            let degraded = prefs.as_ref().is_some_and(|p| {
                p.adjacency_preference
                    .is_some_and(|a| a != AdjacencyPreference::None)
                    && occupied
                        .iter()
                        .filter(|a| {
                            a.preferences
                                .as_ref()
                                .and_then(|p| p.adjacency_group_ref.as_ref())
                                == p.adjacency_group_ref.as_ref()
                        })
                        .count()
                        > 0
                    && !same_group_satisfied(unit, occupied, p)
            });
            let reason = if degraded {
                Some(DegradationReason::NoAdjacentBlock)
            } else {
                None
            };
            let seat_ref = SeatRef {
                seat_allocation_id: allocation_id.clone(),
                allocation_type: unit.allocation_type,
                seat_map_id: Some(map.seat_map_id.clone()),
                seat_map_version: Some(map.seat_map_version),
                seat_unit_ref: Some(unit.seat_unit_ref.clone()),
                coach_no: Some(unit.coach_no.clone()),
                seat_no: Some(unit.seat_no.clone()),
                berth_position: unit.berth_position,
                display_label: format!("{}车 {}", unit.coach_no, unit.seat_no),
                degraded,
                degradation_reason: reason,
            };
            let allocation = Self {
                seat_allocation_id: allocation_id,
                segment_booking_id: cmd.segment_booking_id,
                journey_order_id: cmd.journey_order_id,
                traveler_ref: cmd.traveler_ref,
                segment_ref: cmd.segment_ref,
                scheduled_service_ref: cmd.scheduled_service_ref,
                service_date: cmd.service_date,
                capacity_hold_id: cmd.capacity_hold_id,
                capacity_unit_ref: cmd.capacity_unit_ref,
                interval: cmd.interval,
                seat_ref,
                status: AllocationStatus::Allocated,
                preferences: prefs,
                created_at: now.clone(),
                confirmed_at: None,
                released_at: None,
                expires_at: Some(cmd.expires_at),
                version: 1,
            };
            let mut events = vec![SeatAssignmentEvent::seat_allocated(
                &allocation,
                now.clone(),
            )];
            if degraded {
                events.push(SeatAssignmentEvent::adjacency_degradation(&allocation, now));
            }
            return Ok((allocation, events));
        }
        if prefs.as_ref().is_some_and(|p| p.accept_standing) {
            let seat_ref = SeatRef {
                seat_allocation_id: allocation_id.clone(),
                allocation_type: AllocationType::Standing,
                seat_map_id: None,
                seat_map_version: None,
                seat_unit_ref: None,
                coach_no: None,
                seat_no: None,
                berth_position: None,
                display_label: "STANDING".into(),
                degraded: true,
                degradation_reason: Some(DegradationReason::StandingAssigned),
            };
            let allocation = Self {
                seat_allocation_id: allocation_id,
                segment_booking_id: cmd.segment_booking_id,
                journey_order_id: cmd.journey_order_id,
                traveler_ref: cmd.traveler_ref,
                segment_ref: cmd.segment_ref,
                scheduled_service_ref: cmd.scheduled_service_ref,
                service_date: cmd.service_date,
                capacity_hold_id: cmd.capacity_hold_id,
                capacity_unit_ref: cmd.capacity_unit_ref,
                interval: cmd.interval,
                seat_ref,
                status: AllocationStatus::Standing,
                preferences: prefs,
                created_at: now.clone(),
                confirmed_at: None,
                released_at: None,
                expires_at: Some(cmd.expires_at),
                version: 1,
            };
            return Ok((
                allocation.clone(),
                vec![SeatAssignmentEvent::standing_assigned(&allocation, now)],
            ));
        }
        Err(SeatAssignmentError::DomainRuleViolation(
            "no compatible SeatUnit exists and STANDING is not accepted".into(),
        ))
    }
    pub fn confirm(
        &mut self,
        entitlement_id: String,
        source_event_id: String,
        now: String,
    ) -> Result<SeatAssignmentEvent, SeatAssignmentError> {
        if !matches!(
            self.status,
            AllocationStatus::Allocated | AllocationStatus::Standing
        ) {
            return Ok(SeatAssignmentEvent::noop());
        }
        self.status = AllocationStatus::Confirmed;
        self.confirmed_at = Some(now.clone());
        self.version += 1;
        Ok(SeatAssignmentEvent::seat_allocation_confirmed(
            self,
            entitlement_id,
            source_event_id,
            now,
        ))
    }
    pub fn release(
        &mut self,
        reason: ReleaseReason,
        source_event_id: Option<String>,
        now: String,
    ) -> Result<Option<SeatAssignmentEvent>, SeatAssignmentError> {
        if matches!(
            self.status,
            AllocationStatus::Released
                | AllocationStatus::Expired
                | AllocationStatus::Failed
                | AllocationStatus::Missed
        ) {
            return Ok(None);
        }
        self.status = AllocationStatus::Released;
        self.released_at = Some(now.clone());
        self.version += 1;
        Ok(Some(SeatAssignmentEvent::seat_allocation_released(
            self,
            reason,
            source_event_id,
            now,
        )))
    }
    pub fn expire(
        &mut self,
        source_event_id: String,
        now: String,
    ) -> Result<Option<SeatAssignmentEvent>, SeatAssignmentError> {
        if !matches!(
            self.status,
            AllocationStatus::Allocated | AllocationStatus::Standing
        ) {
            return Ok(None);
        }
        self.status = AllocationStatus::Expired;
        self.released_at = Some(now.clone());
        self.version += 1;
        Ok(Some(SeatAssignmentEvent::seat_allocation_expired(
            self,
            source_event_id,
            now,
        )))
    }
}
fn same_group_satisfied(
    unit: &SeatUnit,
    occupied: &[SeatAllocation],
    pref: &SeatPreferences,
) -> bool {
    let Some(group) = pref.adjacency_group_ref.as_ref() else {
        return true;
    };
    occupied
        .iter()
        .filter(|a| {
            a.preferences
                .as_ref()
                .and_then(|p| p.adjacency_group_ref.as_ref())
                == Some(group)
        })
        .any(|a| {
            a.seat_ref.coach_no == Some(unit.coach_no.clone())
                && (a
                    .seat_ref
                    .seat_no
                    .as_ref()
                    .zip(unit.row_no.as_ref())
                    .is_some())
        })
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CreateSeatMapCommand {
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub composition_version: String,
    pub composition_seed: String,
    pub mapping_version: String,
    #[serde(default)]
    pub change_scenario: Option<String>,
    pub operator_ref: String,
}
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PublishSeatMapCommand {
    pub expected_seat_map_version: i64,
    pub publish_reason: String,
    pub operator_ref: String,
}
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RetireSeatMapCommand {
    pub expected_seat_map_version: i64,
    pub retire_reason: String,
    pub operator_ref: String,
}
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct MarkUnavailableCommand {
    pub expected_seat_map_version: i64,
    pub unavailable_reason: String,
    pub operator_ref: String,
}
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ReopenSeatUnitCommand {
    pub expected_seat_map_version: i64,
    pub reopen_reason: String,
    pub operator_ref: String,
}
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AllocateSeatCommand {
    pub segment_booking_id: String,
    pub journey_order_id: String,
    pub traveler_ref: String,
    pub segment_ref: String,
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub capacity_hold_id: String,
    pub capacity_unit_ref: String,
    pub interval: StationInterval,
    pub class_ref: String,
    pub issue_purpose: String,
    #[serde(default)]
    pub seat_preferences: Option<SeatPreferences>,
    pub expires_at: String,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AllocateSeatResponse {
    pub seat_allocation_id: String,
    pub status: AllocationStatus,
    pub seat_ref: SeatRef,
    pub expires_at: String,
}
impl From<&SeatAllocation> for AllocateSeatResponse {
    fn from(a: &SeatAllocation) -> Self {
        Self {
            seat_allocation_id: a.seat_allocation_id.clone(),
            status: a.status,
            seat_ref: a.seat_ref.clone(),
            expires_at: a.expires_at.clone().unwrap_or_default(),
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PaginatedSeatMaps {
    pub items: Vec<SeatMap>,
    pub total: usize,
    pub limit: usize,
    pub offset: usize,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PaginatedSeatAllocations {
    pub items: Vec<SeatAllocation>,
    pub total: usize,
    pub limit: usize,
    pub offset: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SeatAssignmentEvent {
    Event {
        event_type: &'static str,
        payload: Value,
    },
    Noop,
}
impl SeatAssignmentEvent {
    fn noop() -> Self {
        Self::Noop
    }
    fn event_type(&self) -> &'static str {
        match self {
            Self::Event { event_type, .. } => event_type,
            Self::Noop => "Noop",
        }
    }
    fn payload(&self) -> Value {
        match self {
            Self::Event { payload, .. } => payload.clone(),
            Self::Noop => json!({}),
        }
    }
    pub(crate) fn envelope(
        self,
        id: &str,
        version: i64,
        corr: String,
        cause: Option<String>,
    ) -> Option<rust_kit::messaging::EventEnvelope> {
        if matches!(self, Self::Noop) {
            return None;
        }
        let ty = self.event_type();
        let mut e = rust_kit::messaging::EventEnvelope::canonical(
            ty,
            rust_kit::messaging::valid_or_generated_correlation_id(corr),
            cause,
            crate::profile().service_id,
            self.payload(),
        );
        e.event_id = deterministic_event_id(ty, id, version);
        Some(e)
    }
    fn seat_map_built(m: &SeatMap, t: String) -> Self {
        Self::Event {
            event_type: "SeatMapBuilt",
            payload: json!({"seatMapId":m.seat_map_id,"scheduledServiceRef":m.scheduled_service_ref,"serviceDate":m.service_date,"compositionVersion":m.composition_version,"seatMapVersion":m.seat_map_version,"compositionSeed":m.composition_seed,"mappingVersion":m.mapping_version,"coachCount":m.coaches.len(),"seatUnitCount":m.all_units().count(),"status":"DRAFT","builtAt":t}),
        }
    }
    fn seat_map_published(m: &SeatMap, op: String, t: String) -> Self {
        Self::Event {
            event_type: "SeatMapVersionPublished",
            payload: json!({"seatMapId":m.seat_map_id,"scheduledServiceRef":m.scheduled_service_ref,"serviceDate":m.service_date,"compositionVersion":m.composition_version,"seatMapVersion":m.seat_map_version,"publishedAt":t,"operatorRef":op,"status":"PUBLISHED"}),
        }
    }
    fn seat_map_retired(m: &SeatMap, reason: String, op: String, t: String) -> Self {
        Self::Event {
            event_type: "SeatMapVersionRetired",
            payload: json!({"seatMapId":m.seat_map_id,"scheduledServiceRef":m.scheduled_service_ref,"serviceDate":m.service_date,"seatMapVersion":m.seat_map_version,"retireReason":reason,"retiredAt":t,"operatorRef":op,"status":m.status.as_contract()}),
        }
    }
    fn seat_unit_unavailable(
        id: &str,
        ver: i64,
        su: &str,
        coach: String,
        seat: String,
        reason: String,
        op: String,
        t: String,
    ) -> Self {
        Self::Event {
            event_type: "SeatUnitUnavailableMarked",
            payload: json!({"seatMapId":id,"seatMapVersion":ver,"seatUnitRef":su,"coachNo":coach,"seatNo":seat,"unavailableReason":reason,"markedAt":t,"operatorRef":op}),
        }
    }
    fn seat_unit_reopened(
        id: &str,
        ver: i64,
        su: &str,
        coach: String,
        seat: String,
        reason: String,
        op: String,
        t: String,
    ) -> Self {
        Self::Event {
            event_type: "SeatUnitReopened",
            payload: json!({"seatMapId":id,"seatMapVersion":ver,"seatUnitRef":su,"coachNo":coach,"seatNo":seat,"reopenReason":reason,"reopenedAt":t,"operatorRef":op}),
        }
    }
    fn seat_allocated(a: &SeatAllocation, t: String) -> Self {
        Self::Event {
            event_type: "SeatAllocated",
            payload: json!({"seatAllocationId":a.seat_allocation_id,"segmentBookingId":a.segment_booking_id,"journeyOrderId":a.journey_order_id,"travelerRef":a.traveler_ref,"segmentRef":a.segment_ref,"scheduledServiceRef":a.scheduled_service_ref,"serviceDate":a.service_date,"capacityHoldId":a.capacity_hold_id,"capacityUnitRef":a.capacity_unit_ref,"interval":a.interval,"seatRef":a.seat_ref,"preferences":a.preferences,"allocatedAt":t,"expiresAt":a.expires_at,"status":"ALLOCATED"}),
        }
    }
    fn standing_assigned(a: &SeatAllocation, t: String) -> Self {
        Self::Event {
            event_type: "StandingAssigned",
            payload: json!({"seatAllocationId":a.seat_allocation_id,"segmentBookingId":a.segment_booking_id,"journeyOrderId":a.journey_order_id,"travelerRef":a.traveler_ref,"segmentRef":a.segment_ref,"scheduledServiceRef":a.scheduled_service_ref,"serviceDate":a.service_date,"capacityHoldId":a.capacity_hold_id,"capacityUnitRef":a.capacity_unit_ref,"interval":a.interval,"seatRef":a.seat_ref,"preferences":a.preferences,"assignedAt":t,"expiresAt":a.expires_at,"status":"STANDING"}),
        }
    }
    fn adjacency_degradation(a: &SeatAllocation, t: String) -> Self {
        Self::Event {
            event_type: "AdjacencyDegradationAccepted",
            payload: json!({"adjacencyGroupId":format!("adj-{}", folded_uuid_v7(a.preferences.as_ref().and_then(|p|p.adjacency_group_ref.as_deref()).unwrap_or(&a.seat_allocation_id))),"journeyOrderId":a.journey_order_id,"seatAllocationIds":[a.seat_allocation_id],"acceptedByRef":"POLICY","degradationReason":a.seat_ref.degradation_reason.unwrap_or(DegradationReason::NoAdjacentBlock).as_contract(),"acceptedAt":t}),
        }
    }
    fn seat_allocation_confirmed(
        a: &SeatAllocation,
        entitlement: String,
        source: String,
        t: String,
    ) -> Self {
        Self::Event {
            event_type: "SeatAllocationConfirmed",
            payload: json!({"seatAllocationId":a.seat_allocation_id,"entitlementId":entitlement,"segmentBookingId":a.segment_booking_id,"journeyOrderId":a.journey_order_id,"travelerRef":a.traveler_ref,"seatRef":a.seat_ref,"confirmedAt":t,"sourceEventId":source,"status":"CONFIRMED"}),
        }
    }
    fn seat_allocation_released(
        a: &SeatAllocation,
        reason: ReleaseReason,
        source: Option<String>,
        t: String,
    ) -> Self {
        let mut p = json!({"seatAllocationId":a.seat_allocation_id,"segmentBookingId":a.segment_booking_id,"journeyOrderId":a.journey_order_id,"travelerRef":a.traveler_ref,"capacityHoldId":a.capacity_hold_id,"seatRef":a.seat_ref,"releaseReason":reason.as_contract(),"releasedAt":t,"status":"RELEASED"});
        if let Some(s) = source {
            p["sourceEventId"] = json!(s)
        }
        Self::Event {
            event_type: "SeatAllocationReleased",
            payload: p,
        }
    }
    fn seat_allocation_expired(a: &SeatAllocation, source: String, t: String) -> Self {
        Self::Event {
            event_type: "SeatAllocationExpired",
            payload: json!({"seatAllocationId":a.seat_allocation_id,"segmentBookingId":a.segment_booking_id,"journeyOrderId":a.journey_order_id,"travelerRef":a.traveler_ref,"capacityHoldId":a.capacity_hold_id,"capacityUnitRef":a.capacity_unit_ref,"interval":a.interval,"seatRef":a.seat_ref,"expiredAt":t,"sourceEventId":source,"status":"EXPIRED"}),
        }
    }
}

#[derive(Debug, Clone)]
pub enum SeatAssignmentError {
    ValidationFailed(String),
    NotFound(String),
    Conflict(String),
    IdempotencyKeyReused(String),
    PreconditionFailed(String),
    DomainRuleViolation(String),
    Unavailable(String),
    Internal(String),
}
impl SeatAssignmentError {
    pub(crate) fn code(&self) -> &'static str {
        match self {
            Self::ValidationFailed(_) => "VALIDATION_FAILED",
            Self::NotFound(_) => "NOT_FOUND",
            Self::Conflict(_) => "CONFLICT",
            Self::IdempotencyKeyReused(_) => "IDEMPOTENCY_KEY_REUSED",
            Self::PreconditionFailed(_) => "PRECONDITION_FAILED",
            Self::DomainRuleViolation(_) => "DOMAIN_RULE_VIOLATION",
            Self::Unavailable(_) | Self::Internal(_) => "UNAVAILABLE",
        }
    }
    pub(crate) fn message(&self) -> &str {
        match self {
            Self::ValidationFailed(m)
            | Self::NotFound(m)
            | Self::Conflict(m)
            | Self::IdempotencyKeyReused(m)
            | Self::PreconditionFailed(m)
            | Self::DomainRuleViolation(m)
            | Self::Unavailable(m)
            | Self::Internal(m) => m,
        }
    }
}
impl fmt::Display for SeatAssignmentError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.message())
    }
}
impl std::error::Error for SeatAssignmentError {}
