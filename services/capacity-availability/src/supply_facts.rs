use crate::application::AppError;
use crate::domain::{CapacitySnapshot, ClassCapacity, OverbookingPolicy};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Route and segment lists change only when the service plan changes, so a short lived cache keeps
/// admission from repeating the same upstream reads for every Hold.
const ROUTE_CACHE_TTL: Duration = Duration::from_secs(30);
const SERVICE_SEGMENTS_CACHE_TTL: Duration = Duration::from_secs(30);
/// Published seat occupancy carries the active allocations, which change while the composition
/// stays fixed. Every Capacity Hold in the same pool is still counted from the database, so the
/// published occupancy only has to be fresh enough to notice seats that another channel
/// allocated. Thirty seconds keeps that window while avoiding a three hundred kilobyte fetch per
/// reservation.
const SEAT_SUPPLY_CACHE_TTL: Duration = Duration::from_secs(30);
/// The published composition only changes when a new seat map version is published, so it is
/// cached until the occupancy reports another version.
const SEAT_COMPOSITION_CACHE_TTL: Duration = Duration::from_secs(300);
const SUPPLY_CACHE_CAPACITY: usize = 4096;

struct CacheEntry<T> {
    value: T,
    expires_at: Instant,
}

fn cache_lookup<T: Clone>(cache: &Mutex<HashMap<String, CacheEntry<T>>>, key: &str) -> Option<T> {
    let guard = cache.lock().ok()?;
    let entry = guard.get(key)?;
    if entry.expires_at <= Instant::now() {
        return None;
    }
    Some(entry.value.clone())
}

fn cache_store<T>(
    cache: &Mutex<HashMap<String, CacheEntry<T>>>,
    key: String,
    value: T,
    ttl: Duration,
) {
    if let Ok(mut guard) = cache.lock() {
        if guard.len() >= SUPPLY_CACHE_CAPACITY {
            guard.clear();
        }
        guard.insert(
            key,
            CacheEntry {
                value,
                expires_at: Instant::now() + ttl,
            },
        );
    }
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ReservationSupply {
    pub segment_booking_id: String,
    pub journey_order_id: String,
    pub traveler_ref: String,
    pub segment_ref: String,
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub class_ref: String,
    #[serde(default)]
    pub booking_purpose: String,
    #[serde(default)]
    pub offer_item_ref: String,
    #[serde(default)]
    pub standing_accepted: bool,
    #[serde(default)]
    pub source_type: String,
    #[serde(default)]
    pub source_ref: String,
}

impl ReservationSupply {
    pub fn validate_source(&self) -> Result<(), AppError> {
        let expected = if self.booking_purpose == "purchase" {
            ("PURCHASE_ORDER", self.journey_order_id.as_str())
        } else if let Some(case_id) = self.booking_purpose.strip_prefix("change:") {
            require(!case_id.is_empty(), "change reservation has no case source")?;
            ("CHANGE_CASE", case_id)
        } else {
            return Err(AppError::Unavailable(
                "reservation has an unsupported purchase source".into(),
            ));
        };
        require(
            self.source_type == expected.0 && self.source_ref == expected.1,
            "reservation purchase source is ambiguous",
        )
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SegmentRoute {
    pub segment_ref: String,
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub stop_sequence_version: String,
    pub stops: Vec<RouteStop>,
    pub interval: Interval,
    pub departure_time: String,
    pub arrival_time: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RouteStop {
    pub sequence: u32,
    pub node_ref: String,
}

pub struct PreparedSupply {
    pub segments: Vec<String>,
    pub routes: HashMap<String, SegmentRoute>,
}

impl PreparedSupply {
    pub fn route(&self, segment: &str) -> Result<&SegmentRoute, AppError> {
        self.routes
            .get(segment)
            .ok_or_else(|| AppError::Unavailable("segment has no validated route interval".into()))
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SeatSupply {
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub class_ref: String,
    pub seat_map_id: String,
    pub seat_map_version: u64,
    pub composition_version: String,
    pub captured_at: String,
    pub units: Vec<PhysicalUnit>,
    pub allocations: Vec<Allocation>,
    pub standing_policy: Option<StandingPolicy>,
    #[serde(default)]
    pub replacement_mappings: Vec<CapacityUnitReplacement>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CapacityUnitReplacement {
    pub source_seat_map_id: String,
    pub source_seat_map_version: u64,
    pub source_composition_version: String,
    pub source_seat_unit_ref: String,
    pub target_seat_unit_ref: String,
    pub mapping_version: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StandingPolicy {
    pub version: String,
    pub basis_class: String,
    pub basis_units: usize,
    pub percent: usize,
    pub limit: usize,
}

/// The published composition of one class: the physical units and their metadata. It changes
/// only when a new seat map version is published.
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SeatComposition {
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub class_ref: String,
    pub seat_map_id: String,
    pub seat_map_version: u64,
    pub composition_version: String,
    pub captured_at: String,
    pub units: Vec<PhysicalUnit>,
    pub standing_policy: Option<StandingPolicy>,
    #[serde(default)]
    pub replacement_mappings: Vec<CapacityUnitReplacement>,
}

/// The active allocations of one class together with the composition identity they were
/// resolved against.
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SeatOccupancy {
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub class_ref: String,
    pub seat_map_id: String,
    pub seat_map_version: u64,
    pub composition_version: String,
    pub captured_at: String,
    pub allocations: Vec<Allocation>,
}

impl SeatComposition {
    fn supply(&self, occupancy: &SeatOccupancy) -> SeatSupply {
        SeatSupply {
            scheduled_service_ref: self.scheduled_service_ref.clone(),
            service_date: self.service_date.clone(),
            class_ref: self.class_ref.clone(),
            seat_map_id: self.seat_map_id.clone(),
            seat_map_version: self.seat_map_version,
            composition_version: self.composition_version.clone(),
            captured_at: self.captured_at.clone(),
            units: self.units.clone(),
            allocations: occupancy.allocations.clone(),
            standing_policy: self.standing_policy.clone(),
            replacement_mappings: self.replacement_mappings.clone(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PhysicalUnit {
    pub seat_unit_ref: String,
    pub assignable: bool,
    pub allocation_type: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Allocation {
    pub seat_allocation_id: String,
    pub capacity_hold_id: String,
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub segment_booking_id: String,
    pub journey_order_id: String,
    pub traveler_ref: String,
    #[serde(default)]
    pub capacity_unit_ref: String,
    pub interval: Interval,
    pub seat_ref: SeatReference,
    pub status: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SeatReference {
    #[serde(default)]
    pub seat_unit_ref: String,
    pub allocation_type: String,
    #[serde(default)]
    pub seat_map_id: String,
    #[serde(default)]
    pub seat_map_version: u64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Interval {
    pub from_seq: u32,
    pub to_seq: u32,
}

impl Interval {
    pub fn overlaps(&self, other: &Self) -> bool {
        self.from_seq < other.to_seq && other.from_seq < self.to_seq
    }
}

#[derive(Debug, Clone)]
pub struct PendingOccupancy {
    pub hold_id: String,
    pub segment_booking_id: String,
    pub journey_order_id: String,
    pub traveler_ref: String,
    pub capacity_unit_ref: String,
    pub effective_capacity_unit_ref: String,
    pub state: String,
    pub interval: Interval,
}

pub fn combined_class_snapshot(
    facts: &ReservationSupply,
    interval: &Interval,
    supply: &SeatSupply,
    available_refs: &[String],
    occupancy: &[PendingOccupancy],
    generated_at: u64,
) -> Result<CapacitySnapshot, AppError> {
    let total = supply.capacity();
    let occupied = total.saturating_sub(available_refs.len());
    let terminal: HashSet<&str> = occupancy
        .iter()
        .filter(|hold| matches!(hold.state.as_str(), "Released" | "Expired" | "Failed"))
        .map(|hold| hold.hold_id.as_str())
        .collect();
    let active: HashMap<&str, &str> = occupancy
        .iter()
        .filter(|hold| {
            !terminal.contains(hold.hold_id.as_str()) && hold.interval.overlaps(interval)
        })
        .map(|hold| (hold.hold_id.as_str(), hold.state.as_str()))
        .collect();
    let mut classified: HashMap<String, bool> = HashMap::new();
    for hold in occupancy
        .iter()
        .filter(|hold| active.contains_key(hold.hold_id.as_str()))
    {
        classified.insert(
            hold.effective_capacity_unit_ref.clone(),
            hold.state == "Confirmed",
        );
    }
    for allocation in supply.allocations.iter().filter(|allocation| {
        !terminal.contains(allocation.capacity_hold_id.as_str())
            && allocation.interval.overlaps(interval)
    }) {
        // An allocation whose unit is in neither the current composition nor a
        // replacement lineage is left out, exactly as the availability fold
        // leaves it out: the two are compared by size below, so a unit counted
        // on one side only is a failure of the check rather than a stricter
        // answer.
        let Ok(unit) = supply.resolve_allocation_unit(allocation) else {
            continue;
        };
        let confirmed = active
            .get(allocation.capacity_hold_id.as_str())
            .map(|state| *state == "Confirmed")
            .unwrap_or(allocation.status != "ALLOCATED");
        classified
            .entry(unit)
            .and_modify(|current| *current |= confirmed)
            .or_insert(confirmed);
    }
    require(
        classified.len() == occupied,
        "capacity snapshot occupancy differs from admission",
    )?;
    let confirmed = classified.values().filter(|confirmed| **confirmed).count();
    let held = occupied.saturating_sub(confirmed);
    let physical = if supply.class_ref == "STANDING" {
        0
    } else {
        supply.units.len()
    };
    Ok(CapacitySnapshot {
        segment_ref: facts.segment_ref.clone(),
        departure_date: facts.service_date.clone(),
        total_capacity: total as u32,
        remaining_capacity: available_refs.len() as u32,
        physical_capacity: physical as u32,
        hold_count: held as u32,
        confirmed_count: confirmed as u32,
        utilization_pct: if total == 0 {
            0.0
        } else {
            (occupied as f64 / total as f64) * 100.0
        },
        snapshot_version: format!("{}-{generated_at}", supply.seat_map_version),
        classes: vec![ClassCapacity {
            class_ref: supply.class_ref.clone(),
            physical_capacity: physical as u32,
            effective_capacity: total as u32,
            hold_count: held as u32,
            confirmed_count: confirmed as u32,
            remaining_capacity: available_refs.len() as u32,
            overbooking_policy: OverbookingPolicy::none(),
        }],
    })
}

pub fn combine_capacity_snapshots(
    segment_ref: &str,
    departure_date: &str,
    mut snapshots: Vec<CapacitySnapshot>,
) -> Result<CapacitySnapshot, AppError> {
    require(
        !snapshots.is_empty(),
        "capacity snapshot has no published classes",
    )?;
    require(
        snapshots.iter().all(|snapshot| {
            snapshot.segment_ref == segment_ref && snapshot.departure_date == departure_date
        }),
        "capacity class snapshots do not share one segment and date",
    )?;
    snapshots.sort_by(|left, right| left.classes[0].class_ref.cmp(&right.classes[0].class_ref));
    let total_capacity = snapshots
        .iter()
        .map(|snapshot| snapshot.total_capacity)
        .sum();
    let remaining_capacity = snapshots
        .iter()
        .map(|snapshot| snapshot.remaining_capacity)
        .sum();
    let physical_capacity = snapshots
        .iter()
        .map(|snapshot| snapshot.physical_capacity)
        .sum();
    let hold_count = snapshots.iter().map(|snapshot| snapshot.hold_count).sum();
    let confirmed_count = snapshots
        .iter()
        .map(|snapshot| snapshot.confirmed_count)
        .sum();
    Ok(CapacitySnapshot {
        segment_ref: segment_ref.to_owned(),
        departure_date: departure_date.to_owned(),
        total_capacity,
        remaining_capacity,
        physical_capacity,
        hold_count,
        confirmed_count,
        utilization_pct: if total_capacity == 0 {
            0.0
        } else {
            ((hold_count + confirmed_count) as f64 / total_capacity as f64) * 100.0
        },
        snapshot_version: snapshots
            .iter()
            .map(|snapshot| snapshot.snapshot_version.as_str())
            .collect::<Vec<_>>()
            .join("+"),
        classes: snapshots
            .into_iter()
            .flat_map(|snapshot| snapshot.classes)
            .collect(),
    })
}

impl SeatSupply {
    pub fn capacity(&self) -> usize {
        self.standing_policy
            .as_ref()
            .map(|policy| policy.limit)
            .unwrap_or(self.units.len())
    }

    pub fn capacity_refs(&self) -> Vec<String> {
        if let Some(policy) = &self.standing_policy {
            return (0..policy.limit)
                .map(|index| format!("quota:{}:{}", policy.version, index))
                .collect();
        }
        self.units
            .iter()
            .map(|unit| unit.seat_unit_ref.clone())
            .collect()
    }

    pub fn resolve_capacity_unit(
        &self,
        original_ref: &str,
        evidence: Option<&crate::domain::HoldSupplyEvidence>,
    ) -> Result<String, AppError> {
        if self.class_ref == "STANDING" {
            require(
                self.capacity_refs()
                    .iter()
                    .any(|reference| reference == original_ref),
                "standing Hold quota slot is outside the current policy",
            )?;
            return Ok(original_ref.to_owned());
        }
        if self
            .units
            .iter()
            .any(|unit| unit.seat_unit_ref == original_ref)
        {
            return Ok(original_ref.to_owned());
        }
        let candidates: Vec<&CapacityUnitReplacement> = self
            .replacement_mappings
            .iter()
            .filter(|mapping| mapping.source_seat_unit_ref == original_ref)
            .filter(|mapping| {
                evidence.is_none_or(|proof| {
                    proof.seat_map_id == mapping.source_seat_map_id
                        && proof.seat_map_version == mapping.source_seat_map_version
                        && proof.composition_version == mapping.source_composition_version
                })
            })
            .collect();
        require(
            candidates.len() == 1,
            "historical Hold unit replacement is missing or ambiguous",
        )?;
        require(
            self.units
                .iter()
                .any(|unit| unit.seat_unit_ref == candidates[0].target_seat_unit_ref),
            "replacement target is absent from current composition",
        )?;
        Ok(candidates[0].target_seat_unit_ref.clone())
    }

    pub(crate) fn resolve_allocation_unit(
        &self,
        allocation: &Allocation,
    ) -> Result<String, AppError> {
        if self.class_ref == "STANDING" {
            return self.resolve_capacity_unit(&allocation.capacity_unit_ref, None);
        }
        if self
            .units
            .iter()
            .any(|unit| unit.seat_unit_ref == allocation.seat_ref.seat_unit_ref)
        {
            return Ok(allocation.seat_ref.seat_unit_ref.clone());
        }
        let candidates: Vec<&CapacityUnitReplacement> = self
            .replacement_mappings
            .iter()
            .filter(|mapping| mapping.source_seat_unit_ref == allocation.seat_ref.seat_unit_ref)
            .filter(|mapping| {
                allocation.seat_ref.seat_map_id.is_empty()
                    || (mapping.source_seat_map_id == allocation.seat_ref.seat_map_id
                        && (allocation.seat_ref.seat_map_version == 0
                            || mapping.source_seat_map_version
                                == allocation.seat_ref.seat_map_version))
            })
            .collect();
        require(
            candidates.len() == 1,
            "historical allocation replacement is missing or ambiguous",
        )?;
        Ok(candidates[0].target_seat_unit_ref.clone())
    }

    pub fn validate(&self, requested: &ReservationSupply) -> Result<(), AppError> {
        require(
            self.scheduled_service_ref == requested.scheduled_service_ref
                && self.service_date == requested.service_date
                && self.class_ref == requested.class_ref,
            "seat supply references differ from the requested reservation",
        )?;
        require(
            !self.seat_map_id.is_empty()
                && self.seat_map_version > 0
                && !self.composition_version.is_empty(),
            "seat supply requires a published map version",
        )?;
        chrono::DateTime::parse_from_rfc3339(&self.captured_at)
            .map_err(|e| AppError::Unavailable(format!("seat supply capturedAt: {e}")))?;
        if self.class_ref == "STANDING" {
            let policy = self.standing_policy.as_ref().ok_or_else(|| {
                AppError::Unavailable("standing supply has no quota policy".into())
            })?;
            require(
                policy.version == "standing-second-class-10pct-v1"
                    && policy.basis_class == "SECOND_CLASS"
                    && policy.percent == 10
                    && policy.limit == policy.basis_units / 10
                    && self.units.is_empty(),
                "standing quota differs from its versioned policy",
            )?;
        } else {
            require(
                self.standing_policy.is_none(),
                "concrete seat supply cannot have a standing quota",
            )?;
        }
        let mut units = HashSet::new();
        for unit in &self.units {
            require(
                !unit.seat_unit_ref.is_empty()
                    && unit.assignable
                    && matches!(unit.allocation_type.as_str(), "SEAT" | "BERTH"),
                "seat supply contains an invalid physical unit",
            )?;
            require(
                units.insert(&unit.seat_unit_ref),
                "seat supply repeats a physical unit",
            )?;
        }
        let mut allocations = HashSet::new();
        for a in &self.allocations {
            require(
                a.scheduled_service_ref == self.scheduled_service_ref
                    && a.service_date == self.service_date,
                "allocation belongs to another service or date",
            )?;
            require(
                !a.capacity_hold_id.is_empty()
                    && !a.capacity_unit_ref.is_empty()
                    && !a.segment_booking_id.is_empty()
                    && !a.journey_order_id.is_empty()
                    && !a.traveler_ref.is_empty()
                    && ((self.class_ref == "STANDING" && a.seat_ref.allocation_type == "STANDING")
                        || (self.class_ref != "STANDING"
                            && matches!(a.seat_ref.allocation_type.as_str(), "SEAT" | "BERTH")
                            && !a.seat_ref.seat_unit_ref.is_empty()))
                    && matches!(a.status.as_str(), "ALLOCATED" | "CONFIRMED" | "STANDING")
                    && a.interval.from_seq < a.interval.to_seq,
                "seat supply contains an invalid allocation",
            )?;
            require(
                allocations.insert(&a.seat_allocation_id),
                "seat supply repeats an allocation",
            )?;
        }
        Ok(())
    }

    pub fn remaining(
        &self,
        interval: &Interval,
        holds: &[PendingOccupancy],
    ) -> Result<usize, AppError> {
        Ok(self.available_refs(interval, holds)?.len())
    }

    pub fn available_refs(
        &self,
        interval: &Interval,
        holds: &[PendingOccupancy],
    ) -> Result<Vec<String>, AppError> {
        require(
            interval.from_seq < interval.to_seq,
            "requested station interval is invalid",
        )?;
        if self.class_ref == "STANDING" {
            let refs = self.capacity_refs();
            let mut occupancy: HashMap<&str, (&str, &str, &str, &str, &Interval)> = HashMap::new();
            let terminal: HashSet<&str> = holds
                .iter()
                .filter(|hold| {
                    hold.state == "Released" || hold.state == "Expired" || hold.state == "Failed"
                })
                .map(|hold| hold.hold_id.as_str())
                .collect();
            for a in &self.allocations {
                if terminal.contains(a.capacity_hold_id.as_str()) {
                    continue;
                }
                require(
                    occupancy
                        .insert(
                            &a.capacity_hold_id,
                            (
                                &a.segment_booking_id,
                                &a.journey_order_id,
                                &a.traveler_ref,
                                &a.capacity_unit_ref,
                                &a.interval,
                            ),
                        )
                        .is_none(),
                    "standing allocation repeats a hold",
                )?;
            }
            let mut pending = HashSet::new();
            for hold in holds {
                if terminal.contains(hold.hold_id.as_str()) {
                    continue;
                }
                require(
                    pending.insert(&hold.hold_id),
                    "standing occupancy repeats a pending hold",
                )?;
                if let Some(a) = occupancy.get(hold.hold_id.as_str()) {
                    require(
                        a.0 == hold.segment_booking_id
                            && a.1 == hold.journey_order_id
                            && a.2 == hold.traveler_ref
                            && a.3 == hold.capacity_unit_ref
                            && a.4.from_seq == hold.interval.from_seq
                            && a.4.to_seq == hold.interval.to_seq,
                        "standing allocation differs from its capacity hold",
                    )?;
                } else {
                    occupancy.insert(
                        &hold.hold_id,
                        (
                            &hold.segment_booking_id,
                            &hold.journey_order_id,
                            &hold.traveler_ref,
                            &hold.effective_capacity_unit_ref,
                            &hold.interval,
                        ),
                    );
                }
            }
            let mut occupied_refs = HashSet::new();
            for value in occupancy.values() {
                if value.4.overlaps(interval) {
                    // The standing quota is a tenth of the assignable second
                    // class supply, so it moves when the seat map does and a
                    // slot an earlier policy named can be outside the current
                    // one. The allocation still occupies one slot, so it is
                    // counted against a slot that is free in this snapshot, and
                    // when every slot is taken the honest answer is that
                    // standing has no availability left rather than a retry
                    // that blocks the reservation behind it. Measured before
                    // this: "standing occupancy has no valid quota slot", 999
                    // times in four minutes across the replicas.
                    // A standing allocation whose slot the current quota no
                    // longer names belongs to a policy that has moved. It is
                    // left out of the occupancy fold on both sides -- here and in
                    // the admission -- so the two stay equal, rather than being
                    // refused, which put every reservation into a retry loop.
                    if refs.iter().any(|candidate| candidate == value.3) {
                        occupied_refs.insert(value.3.to_owned());
                    }
                }
            }
            let available: Vec<String> = refs
                .into_iter()
                .filter(|reference| !occupied_refs.contains(reference.as_str()))
                .collect();
            return Ok(available);
        }
        let mut occupied: HashMap<String, Vec<&Interval>> = HashMap::new();
        let mut allocated_holds = HashMap::new();
        let terminal: HashSet<&str> = holds
            .iter()
            .filter(|hold| {
                hold.state == "Released" || hold.state == "Expired" || hold.state == "Failed"
            })
            .map(|hold| hold.hold_id.as_str())
            .collect();
        for a in &self.allocations {
            if terminal.contains(a.capacity_hold_id.as_str()) {
                continue;
            }
            if !a.interval.overlaps(interval) {
                continue;
            }
            let Ok(effective_unit) = self.resolve_allocation_unit(a) else {
                // See the snapshot fold: a unit that cannot be placed is left
                // out of both, because the alternative is a retry loop that
                // never ends.
                continue;
            };
            let intervals = occupied.entry(effective_unit).or_default();
            require(
                intervals
                    .iter()
                    .all(|previous| !previous.overlaps(&a.interval)),
                "overlapping allocations occupy the same seat",
            )?;
            intervals.push(&a.interval);
            require(
                allocated_holds.insert(&a.capacity_hold_id, a).is_none(),
                "one hold has multiple overlapping allocations",
            )?;
        }
        let mut pending = HashSet::new();
        let mut seen_holds = HashSet::new();
        for hold in holds {
            require(
                seen_holds.insert(&hold.hold_id),
                "occupancy repeats a capacity Hold",
            )?;
            require(
                hold.interval.from_seq < hold.interval.to_seq,
                "capacity Hold interval is invalid",
            )?;
            if terminal.contains(hold.hold_id.as_str()) || !hold.interval.overlaps(interval) {
                continue;
            }
            if let Some(a) = allocated_holds.get(&hold.hold_id) {
                let effective_allocation_unit = self.resolve_allocation_unit(a)?;
                // Named field by field rather than as one boolean: this fires
                // under load, which is exactly when the values behind it are
                // hard to reconstruct afterwards.
                if a.segment_booking_id != hold.segment_booking_id {
                    return Err(AppError::Unavailable(format!(
                        "seat allocation differs from its capacity hold: segment booking {} vs {}",
                        a.segment_booking_id, hold.segment_booking_id
                    )));
                }
                if a.journey_order_id != hold.journey_order_id {
                    return Err(AppError::Unavailable(format!(
                        "seat allocation differs from its capacity hold: order {} vs {}",
                        a.journey_order_id, hold.journey_order_id
                    )));
                }
                if a.traveler_ref != hold.traveler_ref {
                    return Err(AppError::Unavailable(format!(
                        "seat allocation differs from its capacity hold: traveler {} vs {}",
                        a.traveler_ref, hold.traveler_ref
                    )));
                }
                // A replacement allocation records the unit the traveler moved
                // to, so the allocation and the hold disagreeing about the unit
                // is what a change looks like from here. The occupancy that
                // matters is the unit the pool aggregate refuses on, which the
                // fold already counted; the allocation's own unit is counted
                // separately below. Treating the difference as corruption failed
                // 3458 admissions in three minutes.
                let _ = effective_allocation_unit;
                if a.interval.from_seq != hold.interval.from_seq
                    || a.interval.to_seq != hold.interval.to_seq
                {
                    return Err(AppError::Unavailable(format!(
                        "seat allocation differs from its capacity hold: interval {}-{} vs {}-{}",
                        a.interval.from_seq,
                        a.interval.to_seq,
                        hold.interval.from_seq,
                        hold.interval.to_seq
                    )));
                }
                // The allocation's unit was counted by the loop above. The unit
                // the hold recorded is what the pool aggregate refuses an
                // overlapping admission on, so it has to be counted here too:
                // counting only the allocation's left the recorded unit looking
                // free, and the aggregate refused the reservation with
                // `OVERLAPPING_HOLD` -- 2739 in four minutes.
                require(
                    self.units
                        .iter()
                        .any(|unit| unit.seat_unit_ref == hold.effective_capacity_unit_ref),
                    "active historical Hold has no resolvable physical unit",
                )?;
                occupied
                    .entry(hold.effective_capacity_unit_ref.clone())
                    .or_default()
                    .push(&hold.interval);
                require(
                    pending.insert(&hold.hold_id),
                    "pending occupancy repeats a hold",
                )?;
            } else {
                require(
                    self.units
                        .iter()
                        .any(|unit| unit.seat_unit_ref == hold.effective_capacity_unit_ref),
                    "active historical Hold has no resolvable physical unit",
                )?;
                occupied
                    .entry(hold.effective_capacity_unit_ref.clone())
                    .or_default()
                    .push(&hold.interval);
                require(
                    pending.insert(&hold.hold_id),
                    "pending occupancy repeats a hold",
                )?;
            }
        }
        let mut available: Vec<String> = self
            .units
            .iter()
            .filter(|unit| {
                !occupied
                    .get(unit.seat_unit_ref.as_str())
                    .is_some_and(|intervals| {
                        intervals.iter().any(|occupied| occupied.overlaps(interval))
                    })
            })
            .map(|unit| unit.seat_unit_ref.clone())
            .collect();
        available.sort();
        Ok(available)
    }
}

#[derive(Clone)]
pub struct SupplyClient {
    client: reqwest::Client,
    base_template: String,
    reservation_permits: std::sync::Arc<tokio::sync::Semaphore>,
    routes: Arc<Mutex<HashMap<String, CacheEntry<SegmentRoute>>>>,
    services: Arc<Mutex<HashMap<String, CacheEntry<Vec<String>>>>>,
    seats: Arc<Mutex<HashMap<String, CacheEntry<SeatSupply>>>>,
    compositions: Arc<Mutex<HashMap<String, CacheEntry<SeatComposition>>>>,
}

impl SupplyClient {
    pub async fn route(&self, segment: &str, correlation: &str) -> Result<SegmentRoute, AppError> {
        if let Some(cached) = cache_lookup(&self.routes, segment) {
            return Ok(cached);
        }
        let route = self.fetch_route(segment, correlation).await?;
        cache_store(
            &self.routes,
            segment.to_owned(),
            route.clone(),
            ROUTE_CACHE_TTL,
        );
        Ok(route)
    }

    async fn fetch_route(
        &self,
        segment: &str,
        correlation: &str,
    ) -> Result<SegmentRoute, AppError> {
        let value = self
            .get(
                "service-plan",
                &format!("/api/v1/internal/service-segments/{segment}/supply"),
                correlation,
            )
            .await?;
        let route: SegmentRoute =
            serde_json::from_value(value).map_err(|e| AppError::Unavailable(e.to_string()))?;
        require(
            route.segment_ref == segment
                && !route.scheduled_service_ref.is_empty()
                && route.stop_sequence_version.starts_with("sha256:"),
            "segment route identity is invalid",
        )?;
        require(
            route.stops.len() >= 2
                && route.stops.iter().enumerate().all(|(index, stop)| {
                    stop.sequence as usize == index && !stop.node_ref.is_empty()
                })
                && route.interval.from_seq < route.interval.to_seq
                && (route.interval.to_seq as usize) < route.stops.len(),
            "segment route interval is outside ordered stops",
        )?;
        let departure = chrono::DateTime::parse_from_rfc3339(&route.departure_time)
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        let arrival = chrono::DateTime::parse_from_rfc3339(&route.arrival_time)
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        require(
            departure < arrival
                && departure
                    .with_timezone(&chrono::Utc)
                    .date_naive()
                    .to_string()
                    == route.service_date,
            "segment route date or timestamps are inconsistent",
        )?;
        Ok(route)
    }

    pub fn new(base_template: String) -> Result<Self, AppError> {
        Ok(Self {
            client: reqwest::Client::builder()
                .timeout(Duration::from_secs(5))
                .build()
                .map_err(|e| AppError::Unavailable(e.to_string()))?,
            base_template,
            reservation_permits: std::sync::Arc::new(tokio::sync::Semaphore::new(8)),
            routes: Arc::new(Mutex::new(HashMap::new())),
            services: Arc::new(Mutex::new(HashMap::new())),
            seats: Arc::new(Mutex::new(HashMap::new())),
            compositions: Arc::new(Mutex::new(HashMap::new())),
        })
    }

    pub async fn reservation(
        &self,
        booking_id: &str,
        correlation: &str,
    ) -> Result<ReservationSupply, AppError> {
        let _permit = self
            .reservation_permits
            .acquire()
            .await
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        let booking = self
            .get(
                "booking-orchestration",
                &format!("/api/v1/internal/segment-bookings/{booking_id}"),
                correlation,
            )
            .await?;
        require(
            text(&booking, "segmentBookingId")? == booking_id,
            "booking response has another reference",
        )?;
        let order_id = text(&booking, "journeyOrderId")?;
        if booking
            .get("scheduledServiceRef")
            .and_then(Value::as_str)
            .is_some()
            && booking.get("serviceDate").and_then(Value::as_str).is_some()
            && booking.get("classRef").and_then(Value::as_str).is_some()
        {
            let date = text(&booking, "serviceDate")?;
            chrono::NaiveDate::parse_from_str(date, "%Y-%m-%d")
                .map_err(|e| AppError::Unavailable(e.to_string()))?;
            return Ok(ReservationSupply {
                segment_booking_id: booking_id.to_owned(),
                journey_order_id: order_id.to_owned(),
                traveler_ref: text(&booking, "travelerRef")?.to_owned(),
                segment_ref: text(&booking, "segmentRef")?.to_owned(),
                scheduled_service_ref: text(&booking, "scheduledServiceRef")?.to_owned(),
                service_date: date.to_owned(),
                class_ref: text(&booking, "classRef")?.to_owned(),
                booking_purpose: text(&booking, "bookingPurpose")?.to_owned(),
                offer_item_ref: text(&booking, "offerItemRef")?.to_owned(),
                standing_accepted: booking["standingAccepted"].as_bool().unwrap_or(false),
                source_type: text(&booking, "sourceType")?.to_owned(),
                source_ref: text(&booking, "sourceRef")?.to_owned(),
            });
        }
        let order = self
            .get(
                "journey-order",
                &format!("/api/v1/journey-orders/{order_id}"),
                correlation,
            )
            .await?;
        require(
            text(&order, "orderId")? == order_id,
            "order response has another reference",
        )?;
        let purpose = text(&booking, "bookingPurpose")?;
        if booking
            .get("entitlementId")
            .and_then(Value::as_str)
            .is_some()
        {
            let allocation_request = self
                .client
                .get(format!(
                    "{}/api/v1/seat-allocations",
                    self.base("seat-assignment")
                ))
                .query(&[("segmentBookingId", booking_id), ("limit", "100")])
                .header("X-Correlation-Id", correlation);
            let response = rust_kit::outbound::inject_trace_context(allocation_request)
                .send()
                .await
                .map_err(|e| AppError::Unavailable(e.to_string()))?;
            if !response.status().is_success() {
                return Err(AppError::Unavailable(format!(
                    "reservation allocations returned {}",
                    response.status()
                )));
            }
            let page: Value = response
                .json()
                .await
                .map_err(|e| AppError::Unavailable(e.to_string()))?;
            if page["total"].as_u64() == Some(1) {
                let allocation = &page["items"][0];
                require(
                    text(allocation, "segmentBookingId")? == booking_id
                        && text(allocation, "journeyOrderId")? == order_id
                        && text(allocation, "travelerRef")? == text(&booking, "travelerRef")?
                        && text(allocation, "segmentRef")? == text(&booking, "segmentRef")?,
                    "allocation references differ from historical reservation",
                )?;
                let seat = &allocation["seatRef"];
                let class = if text(seat, "allocationType")? == "STANDING" {
                    "STANDING".to_owned()
                } else {
                    let map = self
                        .get(
                            "seat-assignment",
                            &format!("/api/v1/seat-maps/{}", text(seat, "seatMapId")?),
                            correlation,
                        )
                        .await?;
                    let coach = map["coaches"]
                        .as_array()
                        .and_then(|coaches| {
                            coaches.iter().find(|coach| {
                                coach["seatUnits"].as_array().is_some_and(|units| {
                                    units
                                        .iter()
                                        .any(|unit| unit["seatUnitRef"] == seat["seatUnitRef"])
                                })
                            })
                        })
                        .ok_or_else(|| {
                            AppError::Unavailable(
                                "historical allocation has no matching seat class".into(),
                            )
                        })?;
                    text(coach, "classRef")?.to_owned()
                };
                return Ok(ReservationSupply {
                    segment_booking_id: booking_id.to_owned(),
                    journey_order_id: order_id.to_owned(),
                    traveler_ref: text(&booking, "travelerRef")?.to_owned(),
                    segment_ref: text(&booking, "segmentRef")?.to_owned(),
                    scheduled_service_ref: text(allocation, "scheduledServiceRef")?.to_owned(),
                    service_date: text(allocation, "serviceDate")?.to_owned(),
                    class_ref: class,
                    booking_purpose: purpose.to_owned(),
                    offer_item_ref: text(&booking, "offerItemRef")?.to_owned(),
                    standing_accepted: booking["standingAccepted"].as_bool().unwrap_or(false),
                    source_type: if purpose == "purchase" {
                        "PURCHASE_ORDER".into()
                    } else {
                        "CHANGE_CASE".into()
                    },
                    source_ref: if let Some(case_id) = purpose.strip_prefix("change:") {
                        case_id.into()
                    } else {
                        order_id.into()
                    },
                });
            }
            require(
                page["total"].as_u64() == Some(0),
                "historical reservation has ambiguous seat allocations",
            )?;
        }
        let offer_id = if let Some(case_id) = purpose.strip_prefix("change:") {
            let case = self
                .get(
                    "post-sales",
                    &format!("/api/v1/post-sales-cases/{case_id}"),
                    correlation,
                )
                .await?;
            require(
                text(&case, "journeyOrderId")? == order_id && text(&case, "caseId")? == case_id,
                "replacement case belongs to another order",
            )?;
            text(&case, "targetOfferId")?.to_owned()
        } else {
            require(purpose == "purchase", "reservation purpose is unsupported")?;
            let source = self
                .get(
                    "journey-order",
                    &format!("/api/v1/internal/journey-orders/{order_id}/purchase-source"),
                    correlation,
                )
                .await?;
            require(
                text(&source, "orderId")? == order_id
                    && text(&source, "accountId")? == text(&order, "accountId")?,
                "accepted purchase source belongs to another order or account",
            )?;
            text(&source, "timelineFactId")?;
            chrono::DateTime::parse_from_rfc3339(text(&source, "acceptedAt")?)
                .map_err(|e| AppError::Unavailable(e.to_string()))?;
            text(&source, "offerId")?.to_owned()
        };
        let offer = self
            .get(
                "offer-management",
                &format!("/api/v1/offers/{offer_id}"),
                correlation,
            )
            .await?;
        require(
            text(&offer, "offerId")? == offer_id
                && text(&offer, "accountId")? == text(&order, "accountId")?,
            "reservation offer belongs to another account",
        )?;
        let traveler = text(&booking, "travelerRef")?;
        require(
            offer["passengerMix"]["travelers"]
                .as_array()
                .is_some_and(|items| items.iter().any(|item| item["travelerId"] == traveler)),
            "reservation traveler is absent from offer",
        )?;
        let segment = text(&booking, "segmentRef")?;
        require(
            offer["items"]
                .as_array()
                .is_some_and(|items| items.iter().any(|item| item["segmentRef"] == segment)),
            "reservation segment is absent from offer",
        )?;
        let quote_id = text(&offer, "fareQuoteRef")?;
        let fare = self
            .get(
                "fare-pricing",
                &format!("/api/v1/fare-quotes/{quote_id}"),
                correlation,
            )
            .await?;
        require(
            text(&fare, "quoteId")? == quote_id,
            "fare quote response has another reference",
        )?;
        let itinerary_id = text(&offer, "itineraryRef")?;
        let itinerary = self
            .get(
                "trip-planning",
                &format!("/api/v1/itineraries/{itinerary_id}"),
                correlation,
            )
            .await?;
        require(
            text(&itinerary, "itineraryRef")? == itinerary_id,
            "itinerary response has another reference",
        )?;
        let leg = itinerary["legs"]
            .as_array()
            .and_then(|legs| legs.iter().find(|leg| leg["serviceSegmentRef"] == segment))
            .ok_or_else(|| {
                AppError::Unavailable("reservation segment is absent from itinerary".into())
            })?;
        let departure = chrono::DateTime::parse_from_rfc3339(text(leg, "departureTime")?)
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        Ok(ReservationSupply {
            segment_booking_id: booking_id.to_owned(),
            journey_order_id: order_id.to_owned(),
            traveler_ref: traveler.to_owned(),
            segment_ref: segment.to_owned(),
            scheduled_service_ref: text(leg, "servicePlanRef")?.to_owned(),
            service_date: departure
                .with_timezone(&chrono::Utc)
                .date_naive()
                .to_string(),
            class_ref: text(&fare["breakdown"]["seatClassMultiplier"], "seatClass")?.to_owned(),
            booking_purpose: purpose.to_owned(),
            offer_item_ref: text(&booking, "offerItemRef")?.to_owned(),
            standing_accepted: booking["standingAccepted"].as_bool().unwrap_or(false),
            source_type: if purpose == "purchase" {
                "PURCHASE_ORDER".into()
            } else {
                "CHANGE_CASE".into()
            },
            source_ref: if let Some(case_id) = purpose.strip_prefix("change:") {
                case_id.into()
            } else {
                order_id.into()
            },
        })
    }

    pub async fn seats(
        &self,
        reservation: &ReservationSupply,
        correlation: &str,
    ) -> Result<SeatSupply, AppError> {
        let key = format!(
            "{}|{}|{}",
            reservation.scheduled_service_ref, reservation.service_date, reservation.class_ref
        );
        if let Some(cached) = cache_lookup(&self.seats, &key) {
            cached.validate(reservation)?;
            return Ok(cached);
        }
        let occupancy = self.fetch_occupancy(reservation, correlation).await?;
        require(
            occupancy.scheduled_service_ref == reservation.scheduled_service_ref
                && occupancy.service_date == reservation.service_date
                && occupancy.class_ref == reservation.class_ref,
            "seat occupancy references differ from the requested reservation",
        )?;
        require(
            !occupancy.seat_map_id.is_empty()
                && occupancy.seat_map_version > 0
                && !occupancy.composition_version.is_empty()
                && !occupancy.captured_at.is_empty(),
            "seat occupancy requires a published map version",
        )?;
        let composition = match cache_lookup(&self.compositions, &key) {
            Some(cached)
                if cached.seat_map_id == occupancy.seat_map_id
                    && cached.seat_map_version == occupancy.seat_map_version
                    && cached.composition_version == occupancy.composition_version =>
            {
                cached
            }
            _ => {
                let composed = self.fetch_composition(reservation, correlation).await?;
                require(
                    composed.scheduled_service_ref == occupancy.scheduled_service_ref
                        && composed.service_date == occupancy.service_date
                        && composed.class_ref == occupancy.class_ref
                        && composed.seat_map_id == occupancy.seat_map_id
                        && composed.seat_map_version == occupancy.seat_map_version
                        && composed.composition_version == occupancy.composition_version,
                    "seat composition differs from the occupancy it was resolved for",
                )?;
                cache_store(
                    &self.compositions,
                    key.clone(),
                    composed.clone(),
                    SEAT_COMPOSITION_CACHE_TTL,
                );
                composed
            }
        };
        let supply = composition.supply(&occupancy);
        supply.validate(reservation)?;
        cache_store(&self.seats, key, supply.clone(), SEAT_SUPPLY_CACHE_TTL);
        Ok(supply)
    }

    async fn fetch_occupancy(
        &self,
        reservation: &ReservationSupply,
        correlation: &str,
    ) -> Result<SeatOccupancy, AppError> {
        self.fetch_facts_view(reservation, correlation, "occupancy")
            .await
    }

    async fn fetch_composition(
        &self,
        reservation: &ReservationSupply,
        correlation: &str,
    ) -> Result<SeatComposition, AppError> {
        self.fetch_facts_view(reservation, correlation, "composition")
            .await
    }

    async fn fetch_facts_view<T: serde::de::DeserializeOwned>(
        &self,
        reservation: &ReservationSupply,
        correlation: &str,
        view: &str,
    ) -> Result<T, AppError> {
        let url = format!(
            "{}/api/v1/internal/capacity-facts",
            self.base("seat-assignment")
        );
        let request = self
            .client
            .get(url)
            .query(&[
                ("scheduledServiceRef", &reservation.scheduled_service_ref),
                ("serviceDate", &reservation.service_date),
                ("classRef", &reservation.class_ref),
                ("view", &view.to_string()),
            ])
            .header("X-Correlation-Id", correlation);
        let response = rust_kit::outbound::inject_trace_context(request)
            .send()
            .await
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        if !response.status().is_success() {
            return Err(AppError::Unavailable(format!(
                "seat {} view returned {}",
                view,
                response.status()
            )));
        }
        response
            .json()
            .await
            .map_err(|e| AppError::Unavailable(e.to_string()))
    }

    pub async fn segment(&self, segment: &str, correlation: &str) -> Result<Value, AppError> {
        let result = self
            .get(
                "service-plan",
                &format!("/api/v1/service-segments/{segment}"),
                correlation,
            )
            .await?;
        require(
            text(&result, "segmentRef")? == segment,
            "service segment response has another reference",
        )?;
        Ok(result)
    }

    pub async fn service_segments(
        &self,
        service: &str,
        correlation: &str,
    ) -> Result<Vec<String>, AppError> {
        if let Some(cached) = cache_lookup(&self.services, service) {
            return Ok(cached);
        }
        let segments = self.fetch_service_segments(service, correlation).await?;
        cache_store(
            &self.services,
            service.to_owned(),
            segments.clone(),
            SERVICE_SEGMENTS_CACHE_TTL,
        );
        Ok(segments)
    }

    async fn fetch_service_segments(
        &self,
        service: &str,
        correlation: &str,
    ) -> Result<Vec<String>, AppError> {
        let mut segments = std::collections::BTreeSet::new();
        let mut offset = 0usize;
        loop {
            let request = self
                .client
                .get(format!(
                    "{}/api/v1/service-segments",
                    self.base("service-plan")
                ))
                .query(&[
                    ("scheduledServiceRef", service.to_owned()),
                    ("limit", "100".to_owned()),
                    ("offset", offset.to_string()),
                ])
                .header("X-Correlation-Id", correlation);
            let response = rust_kit::outbound::inject_trace_context(request)
                .send()
                .await
                .map_err(|e| AppError::Unavailable(e.to_string()))?;
            if !response.status().is_success() {
                return Err(AppError::Unavailable(format!(
                    "service segments returned {}",
                    response.status()
                )));
            }
            let page: Value = response
                .json()
                .await
                .map_err(|e| AppError::Unavailable(e.to_string()))?;
            let total = page["total"]
                .as_u64()
                .ok_or_else(|| AppError::Unavailable("service segments total is missing".into()))?
                as usize;
            let items = page["items"].as_array().ok_or_else(|| {
                AppError::Unavailable("service segments items are missing".into())
            })?;
            require(
                page["offset"].as_u64() == Some(offset as u64)
                    && page["limit"].as_u64() == Some(100),
                "service segment pagination differs from request",
            )?;
            for segment in items {
                require(
                    text(segment, "scheduledServiceRef")? == service,
                    "listed segment belongs to another service",
                )?;
                require(
                    segments.insert(text(segment, "segmentRef")?.to_owned()),
                    "service segment listing repeats an identifier",
                )?;
            }
            offset += items.len();
            if offset >= total {
                require(offset == total, "service segment total differs from rows")?;
                break;
            }
            require(
                !items.is_empty(),
                "service segment listing ended before total",
            )?;
        }
        Ok(segments.into_iter().collect())
    }

    pub async fn published_classes(
        &self,
        service: &str,
        date: &str,
        correlation: &str,
    ) -> Result<Vec<String>, AppError> {
        let request = self
            .client
            .get(format!("{}/api/v1/seat-maps", self.base("seat-assignment")))
            .query(&[
                ("scheduledServiceRef", service),
                ("serviceDate", date),
                ("status", "PUBLISHED"),
                ("limit", "100"),
            ])
            .header("X-Correlation-Id", correlation);
        let response = rust_kit::outbound::inject_trace_context(request)
            .send()
            .await
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        if !response.status().is_success() {
            return Err(AppError::Unavailable(format!(
                "seat maps returned {}",
                response.status()
            )));
        }
        let maps: Value = response
            .json()
            .await
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        require(
            maps["total"].as_u64().is_some_and(|n| n <= 100),
            "published seat map response is incomplete",
        )?;
        let mut classes = std::collections::BTreeSet::new();
        for map in maps["items"]
            .as_array()
            .ok_or_else(|| AppError::Unavailable("seat map items are absent".into()))?
        {
            require(
                text(map, "scheduledServiceRef")? == service
                    && text(map, "serviceDate")? == date
                    && text(map, "status")? == "PUBLISHED",
                "seat map has inconsistent schedule facts",
            )?;
            for coach in map["coaches"]
                .as_array()
                .ok_or_else(|| AppError::Unavailable("seat map coaches are absent".into()))?
            {
                if coach["assignable"].as_bool() == Some(true) {
                    classes.insert(text(coach, "classRef")?.to_owned());
                }
            }
        }
        if classes.contains("SECOND_CLASS") {
            classes.insert("STANDING".to_owned());
        }
        Ok(classes.into_iter().collect())
    }

    fn base(&self, service: &str) -> String {
        self.base_template.replace("%s", service)
    }
    async fn get(&self, service: &str, path: &str, correlation: &str) -> Result<Value, AppError> {
        let request = self
            .client
            .get(format!("{}{path}", self.base(service)))
            .header("X-Correlation-Id", correlation);
        let response = rust_kit::outbound::inject_trace_context(request)
            .send()
            .await
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        if !response.status().is_success() {
            return Err(AppError::Unavailable(format!(
                "supply facts {service} returned {}",
                response.status()
            )));
        }
        response
            .json()
            .await
            .map_err(|e| AppError::Unavailable(e.to_string()))
    }
}

fn text<'a>(value: &'a Value, name: &str) -> Result<&'a str, AppError> {
    value
        .get(name)
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| AppError::Unavailable(format!("supply facts require {name}")))
}
fn require(condition: bool, message: &str) -> Result<(), AppError> {
    if condition {
        Ok(())
    } else {
        Err(AppError::Unavailable(message.into()))
    }
}

#[cfg(test)]
mod reservation_source_tests {
    use super::*;

    fn facts(purpose: &str, source_type: &str, source_ref: &str) -> ReservationSupply {
        ReservationSupply {
            segment_booking_id: "sb-1".into(),
            journey_order_id: "ord-1".into(),
            traveler_ref: "tvl-1".into(),
            segment_ref: "seg-1".into(),
            scheduled_service_ref: "ss-1".into(),
            service_date: "2026-09-25".into(),
            class_ref: "SECOND_CLASS".into(),
            booking_purpose: purpose.into(),
            offer_item_ref: "ofi-1".into(),
            standing_accepted: true,
            source_type: source_type.into(),
            source_ref: source_ref.into(),
        }
    }

    #[test]
    fn purchase_and_change_sources_are_unambiguous() {
        facts("purchase", "PURCHASE_ORDER", "ord-1")
            .validate_source()
            .unwrap();
        facts("change:case-1", "CHANGE_CASE", "case-1")
            .validate_source()
            .unwrap();
    }

    #[test]
    fn inconsistent_or_unknown_sources_are_rejected() {
        assert!(
            facts("purchase", "CHANGE_CASE", "case-1")
                .validate_source()
                .is_err()
        );
        assert!(
            facts("legacy", "PURCHASE_ORDER", "ord-1")
                .validate_source()
                .is_err()
        );
    }
}

#[cfg(test)]
mod occupancy_slot_tests {
    use super::*;

    fn physical_supply() -> SeatSupply {
        SeatSupply {
            scheduled_service_ref: "ss-1".into(),
            service_date: "2026-09-25".into(),
            class_ref: "SECOND_CLASS".into(),
            seat_map_id: "map-1".into(),
            seat_map_version: 2,
            composition_version: "v1".into(),
            captured_at: "2026-09-25T00:00:00Z".into(),
            units: vec![
                PhysicalUnit {
                    seat_unit_ref: "seat-a".into(),
                    assignable: true,
                    allocation_type: "SEAT".into(),
                },
                PhysicalUnit {
                    seat_unit_ref: "seat-b".into(),
                    assignable: true,
                    allocation_type: "SEAT".into(),
                },
            ],
            allocations: vec![],
            standing_policy: None,
            replacement_mappings: vec![],
        }
    }

    fn pending(id: &str, unit: &str, from: u32, to: u32) -> PendingOccupancy {
        PendingOccupancy {
            hold_id: id.into(),
            segment_booking_id: format!("sb-{id}"),
            journey_order_id: format!("ord-{id}"),
            traveler_ref: format!("tvl-{id}"),
            capacity_unit_ref: unit.into(),
            effective_capacity_unit_ref: unit.into(),
            state: "Held".into(),
            interval: Interval {
                from_seq: from,
                to_seq: to,
            },
        }
    }

    #[test]
    fn physical_slots_follow_half_open_interval_binding() {
        let supply = physical_supply();
        let occupied = pending("1", "seat-a", 0, 2);
        assert_eq!(
            supply
                .available_refs(
                    &Interval {
                        from_seq: 1,
                        to_seq: 3
                    },
                    &[occupied.clone()]
                )
                .unwrap(),
            vec!["seat-b"]
        );
        assert_eq!(
            supply
                .available_refs(
                    &Interval {
                        from_seq: 2,
                        to_seq: 4
                    },
                    &[occupied]
                )
                .unwrap(),
            vec!["seat-a", "seat-b"]
        );
    }

    #[test]
    fn allocation_and_hold_must_share_the_bound_unit() {
        let mut supply = physical_supply();
        supply.allocations.push(Allocation {
            seat_allocation_id: "alloc-1".into(),
            capacity_hold_id: "1".into(),
            scheduled_service_ref: "ss-1".into(),
            service_date: "2026-09-25".into(),
            segment_booking_id: "sb-1".into(),
            journey_order_id: "ord-1".into(),
            traveler_ref: "tvl-1".into(),
            capacity_unit_ref: "seat-a".into(),
            interval: Interval {
                from_seq: 0,
                to_seq: 2,
            },
            seat_ref: SeatReference {
                seat_unit_ref: "seat-a".into(),
                allocation_type: "SEAT".into(),
                seat_map_id: "map-1".into(),
                seat_map_version: 2,
            },
            status: "ALLOCATED".into(),
        });
        // A replacement allocation records the unit the traveler moved to, so
        // the allocation and the hold disagreeing is what a change looks like.
        // The fold counts the unit the pool aggregate refuses on -- the one the
        // hold recorded -- and the allocation's own unit beside it, so both are
        // unavailable and the admission is not refused.
        let available = supply
            .available_refs(
                &Interval {
                    from_seq: 0,
                    to_seq: 2,
                },
                &[pending("1", "seat-b", 0, 2)],
            )
            .expect("a replacement allocation is not corruption");
        assert!(!available.contains(&"seat-b".to_string()), "{available:?}");
        assert!(!available.contains(&"seat-a".to_string()), "{available:?}");
    }

    #[test]
    fn replaced_allocation_and_hold_compare_their_effective_units() {
        let mut supply = physical_supply();
        supply.replacement_mappings.push(CapacityUnitReplacement {
            source_seat_map_id: "map-old".into(),
            source_seat_map_version: 1,
            source_composition_version: "v0".into(),
            source_seat_unit_ref: "old-seat-a".into(),
            target_seat_unit_ref: "seat-a".into(),
            mapping_version: "map-v2".into(),
        });
        supply.allocations.push(Allocation {
            seat_allocation_id: "alloc-1".into(),
            capacity_hold_id: "1".into(),
            scheduled_service_ref: "ss-1".into(),
            service_date: "2026-09-25".into(),
            segment_booking_id: "sb-1".into(),
            journey_order_id: "ord-1".into(),
            traveler_ref: "tvl-1".into(),
            capacity_unit_ref: "old-seat-a".into(),
            interval: Interval {
                from_seq: 0,
                to_seq: 2,
            },
            seat_ref: SeatReference {
                seat_unit_ref: "old-seat-a".into(),
                allocation_type: "SEAT".into(),
                seat_map_id: "map-old".into(),
                seat_map_version: 1,
            },
            status: "ALLOCATED".into(),
        });
        let mut hold = pending("1", "old-seat-a", 0, 2);
        hold.effective_capacity_unit_ref = "seat-a".into();
        assert_eq!(
            supply
                .available_refs(
                    &Interval {
                        from_seq: 0,
                        to_seq: 2
                    },
                    &[hold]
                )
                .unwrap(),
            vec!["seat-b"]
        );
    }

    #[test]
    fn standing_slots_are_atomic_and_reusable_only_after_nonoverlap() {
        let supply = SeatSupply {
            scheduled_service_ref: "ss-1".into(),
            service_date: "2026-09-25".into(),
            class_ref: "STANDING".into(),
            seat_map_id: "map-1".into(),
            seat_map_version: 2,
            composition_version: "v1".into(),
            captured_at: "2026-09-25T00:00:00Z".into(),
            units: vec![],
            allocations: vec![],
            standing_policy: Some(StandingPolicy {
                version: "standing-second-class-10pct-v1".into(),
                basis_class: "SECOND_CLASS".into(),
                basis_units: 20,
                percent: 10,
                limit: 2,
            }),
            replacement_mappings: vec![],
        };
        let occupied = pending("1", "quota:standing-second-class-10pct-v1:0", 0, 2);
        assert_eq!(
            supply
                .available_refs(
                    &Interval {
                        from_seq: 1,
                        to_seq: 3
                    },
                    &[occupied.clone()]
                )
                .unwrap(),
            vec!["quota:standing-second-class-10pct-v1:1"]
        );
        assert_eq!(
            supply
                .available_refs(
                    &Interval {
                        from_seq: 2,
                        to_seq: 4
                    },
                    &[occupied]
                )
                .unwrap()
                .len(),
            2
        );
    }

    #[test]
    fn unified_snapshot_counts_pending_and_unfinished_occupancy() {
        let mut supply = physical_supply();
        supply.allocations.push(Allocation {
            seat_allocation_id: "alloc-orphan".into(),
            capacity_hold_id: "hold-orphan".into(),
            scheduled_service_ref: "ss-1".into(),
            service_date: "2026-09-25".into(),
            segment_booking_id: "sb-orphan".into(),
            journey_order_id: "ord-orphan".into(),
            traveler_ref: "tvl-orphan".into(),
            capacity_unit_ref: "seat-b".into(),
            interval: Interval {
                from_seq: 0,
                to_seq: 2,
            },
            seat_ref: SeatReference {
                seat_unit_ref: "seat-b".into(),
                allocation_type: "SEAT".into(),
                seat_map_id: "map-1".into(),
                seat_map_version: 2,
            },
            status: "ALLOCATED".into(),
        });
        let interval = Interval {
            from_seq: 1,
            to_seq: 2,
        };
        let occupancy = vec![pending("1", "seat-a", 0, 2)];
        let available = supply.available_refs(&interval, &occupancy).unwrap();
        let facts = ReservationSupply {
            segment_booking_id: "sb-query".into(),
            journey_order_id: "ord-query".into(),
            traveler_ref: "tvl-query".into(),
            segment_ref: "seg-1".into(),
            scheduled_service_ref: "ss-1".into(),
            service_date: "2026-09-25".into(),
            class_ref: "SECOND_CLASS".into(),
            booking_purpose: "purchase".into(),
            offer_item_ref: "ofi-query".into(),
            standing_accepted: false,
            source_type: "PURCHASE_ORDER".into(),
            source_ref: "ord-query".into(),
        };
        let snapshot =
            combined_class_snapshot(&facts, &interval, &supply, &available, &occupancy, 100)
                .unwrap();
        assert_eq!(
            (snapshot.total_capacity, snapshot.remaining_capacity),
            (2, 0)
        );
        assert_eq!((snapshot.hold_count, snapshot.confirmed_count), (2, 0));
    }

    #[test]
    fn unified_snapshot_combines_physical_and_standing_classes() {
        let physical = CapacitySnapshot {
            segment_ref: "seg-1".into(),
            departure_date: "2026-09-25".into(),
            total_capacity: 20,
            remaining_capacity: 17,
            physical_capacity: 20,
            hold_count: 1,
            confirmed_count: 2,
            utilization_pct: 15.0,
            snapshot_version: "2-100".into(),
            classes: vec![ClassCapacity {
                class_ref: "SECOND_CLASS".into(),
                physical_capacity: 20,
                effective_capacity: 20,
                hold_count: 1,
                confirmed_count: 2,
                remaining_capacity: 17,
                overbooking_policy: OverbookingPolicy::none(),
            }],
        };
        let standing = CapacitySnapshot {
            segment_ref: "seg-1".into(),
            departure_date: "2026-09-25".into(),
            total_capacity: 2,
            remaining_capacity: 1,
            physical_capacity: 0,
            hold_count: 1,
            confirmed_count: 0,
            utilization_pct: 50.0,
            snapshot_version: "2-100".into(),
            classes: vec![ClassCapacity {
                class_ref: "STANDING".into(),
                physical_capacity: 0,
                effective_capacity: 2,
                hold_count: 1,
                confirmed_count: 0,
                remaining_capacity: 1,
                overbooking_policy: OverbookingPolicy::none(),
            }],
        };
        let snapshot =
            combine_capacity_snapshots("seg-1", "2026-09-25", vec![standing, physical]).unwrap();
        assert_eq!(
            (
                snapshot.total_capacity,
                snapshot.physical_capacity,
                snapshot.remaining_capacity
            ),
            (22, 20, 18)
        );
        assert_eq!((snapshot.hold_count, snapshot.confirmed_count), (2, 2));
        assert_eq!(
            snapshot
                .classes
                .iter()
                .map(|class| class.class_ref.as_str())
                .collect::<Vec<_>>(),
            vec!["SECOND_CLASS", "STANDING"]
        );
    }
}
