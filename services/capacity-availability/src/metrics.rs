//! Low-cardinality capacity telemetry.
//!
//! Contract: `docs/07-observability/business-metrics.md`. Two raw instruments
//! leave this service:
//!
//! * `train_ticket.capacity.units` (gauge) by `state`, `travel_class`, `seat_mode`;
//! * `train_ticket.capacity.hold.oldest_age` (gauge, seconds) by `state`, `travel_class`.
//!
//! WHY EVERY LEVEL COMES FROM THE DATABASE
//! ---------------------------------------
//! `InventoryPool` holds the live aggregate, but a process that restarts with an
//! empty aggregate would publish zero for a run that is not zero. Both levels
//! are therefore reconstructed from `inventory_pool_snapshots`, the durable
//! document each pool is written as, and the reconstruction is a pure fold over
//! those rows -- which is what the restart test exercises.
//!
//! The document is also the bounded source. `capacity_hold_snapshots` keeps one
//! row per hold ever created for audit and is never pruned, so aggregating it on
//! a timer would scan a table that only grows; the pool document holds the live
//! holds plus the terminal ones still inside the aggregate's retention window,
//! and there is one row per pool.
//!
//! WHY NO IDENTIFIER IS A LABEL
//! ----------------------------
//! Hold, pool, segment, service, date, coach and seat references are identifying
//! values: one series each would be unbounded. The fold's inputs carry them only
//! so rows can be joined and bucketed; only the closed enums below reach an
//! attribute. A class or mode outside the allowlist collapses to `other`.

use std::collections::BTreeMap;
use std::sync::{Arc, RwLock};

use opentelemetry::KeyValue;
use opentelemetry::metrics::Meter;

pub const CAPACITY_UNITS_METRIC: &str = "train_ticket.capacity.units";
pub const CAPACITY_HOLD_OLDEST_AGE_METRIC: &str = "train_ticket.capacity.hold.oldest_age";

/// The disposition of a capacity unit, as published on `train_ticket.capacity.units`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum CapacityUnitState {
    /// Effective capacity that no live hold currently blocks.
    Sellable,
    /// Units under a `Held` hold that has not expired.
    Held,
    /// Units under a `Confirmed` hold.
    Confirmed,
    /// Units whose hold reached `Released`, `Expired` or `Failed` and which the
    /// durable document still retains. The aggregate retires a terminal hold
    /// fifteen minutes after it reaches that state, so this is a recent-release
    /// window rather than a lifetime total.
    Released,
    /// Units blocked by any live hold, whether held or confirmed.
    Occupied,
}

impl CapacityUnitState {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Sellable => "sellable",
            Self::Held => "held",
            Self::Confirmed => "confirmed",
            Self::Released => "released",
            Self::Occupied => "occupied",
        }
    }
}

/// The nonterminal hold states published on the age gauge.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum HoldAgeState {
    Requested,
    Held,
    Confirmed,
}

impl HoldAgeState {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Requested => "requested",
            Self::Held => "held",
            Self::Confirmed => "confirmed",
        }
    }
}

/// Bounded travel-class vocabulary. Anything else is `other`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum TravelClass {
    FirstClass,
    SecondClass,
    SleeperHard,
    SleeperSoft,
    Business,
    Standing,
    Standard,
    Other,
}

impl TravelClass {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::FirstClass => "first_class",
            Self::SecondClass => "second_class",
            Self::SleeperHard => "sleeper_hard",
            Self::SleeperSoft => "sleeper_soft",
            Self::Business => "business",
            Self::Standing => "standing",
            Self::Standard => "standard",
            Self::Other => "other",
        }
    }

    pub fn from_ref(value: &str) -> Self {
        match value.trim().to_ascii_uppercase().as_str() {
            "FIRST_CLASS" | "FIRST" | "FIRSTCLASS" => Self::FirstClass,
            "SECOND_CLASS" | "SECOND" | "SECONDCLASS" => Self::SecondClass,
            "SLEEPER_HARD" | "HARD_SLEEPER" | "SLEEPER_HARD_BERTH" => Self::SleeperHard,
            "SLEEPER_SOFT" | "SOFT_SLEEPER" | "SLEEPER_SOFT_BERTH" => Self::SleeperSoft,
            "BUSINESS" | "BUSINESS_CLASS" => Self::Business,
            "STANDING" | "STANDEE" => Self::Standing,
            "STANDARD" | "STANDARD_CLASS" => Self::Standard,
            _ => Self::Other,
        }
    }
}

/// Bounded seat-mode vocabulary. Anything else is `other`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum SeatMode {
    Seat,
    Berth,
    Standing,
    Other,
}

impl SeatMode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Seat => "seat",
            Self::Berth => "berth",
            Self::Standing => "standing",
            Self::Other => "other",
        }
    }

    pub fn from_ref(value: &str) -> Self {
        match value.trim().to_ascii_uppercase().as_str() {
            "SEAT" | "RECLINER" | "CHAIR" => Self::Seat,
            "BERTH" | "SLEEPER" | "COUCHETTE" => Self::Berth,
            "STANDING" | "STANDING_QUOTA" | "STANDEE" => Self::Standing,
            _ => Self::Other,
        }
    }
}

/// One hold as the durable pool document records it. `hold_id` is carried so a
/// row can be traced back during an investigation; it never becomes a label.
#[derive(Debug, Clone, PartialEq)]
pub struct DurableHoldFact {
    pub hold_id: String,
    /// The durable `state` string: `Held`, `Confirmed`, `Released` and so on.
    pub state: String,
    pub requested_at_millis: u64,
    pub expires_at_millis: u64,
    pub confirmed_at_millis: Option<u64>,
    pub accepted_class_ref: Option<String>,
    pub occupancy_class_ref: Option<String>,
}

/// One inventory pool's durable identity, capacity and holds, read from
/// `inventory_pool_snapshots`.
#[derive(Debug, Clone, PartialEq)]
pub struct DurablePoolFact {
    pub pool_id: String,
    pub class_ref: String,
    pub sellable_unit_type_ref: String,
    pub physical_unit_count: u64,
    pub max_overbooking_pct: f64,
    pub holds: Vec<DurableHoldFact>,
}

impl DurablePoolFact {
    pub fn seat_mode(&self) -> SeatMode {
        SeatMode::from_ref(&self.sellable_unit_type_ref)
    }

    /// The same effective capacity `InventoryPool::effective_capacity` reports:
    /// a standing pool is its quota, everything else is the physical count
    /// widened by the pool's own overbooking policy.
    pub fn effective_capacity(&self) -> i64 {
        if self.seat_mode() == SeatMode::Standing {
            return i64::try_from(self.physical_unit_count).unwrap_or(i64::MAX);
        }
        let physical = self.physical_unit_count as f64;
        let widened = physical * (1.0 + self.max_overbooking_pct.max(0.0) / 100.0);
        widened.floor() as i64
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CapacityUnitRow {
    pub state: CapacityUnitState,
    pub travel_class: TravelClass,
    pub seat_mode: SeatMode,
    pub units: i64,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct HoldAgeRow {
    pub state: HoldAgeState,
    pub travel_class: TravelClass,
    pub seconds: f64,
}

/// The complete level a single refresh publishes. Both vectors are label-only:
/// nothing here can grow with the number of holds, pools or travelers.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct CapacityMetricsSnapshot {
    pub units: Vec<CapacityUnitRow>,
    pub hold_age: Vec<HoldAgeRow>,
}

/// Fold durable rows into the published levels.
///
/// Pure, so the restart test can hand the same durable rows to a second call and
/// prove the levels repeat instead of resetting.
pub fn build_snapshot(pools: &[DurablePoolFact], now_millis: u64) -> CapacityMetricsSnapshot {
    let mut units: BTreeMap<(CapacityUnitState, TravelClass, SeatMode), i64> = BTreeMap::new();
    let mut ages: BTreeMap<(HoldAgeState, TravelClass), f64> = BTreeMap::new();
    let mut sellable: BTreeMap<(TravelClass, SeatMode), i64> = BTreeMap::new();

    for pool in pools {
        let mut blocked = 0_i64;
        for hold in &pool.holds {
            let class_ref = hold
                .accepted_class_ref
                .as_deref()
                .filter(|value| !value.trim().is_empty())
                .unwrap_or(pool.class_ref.as_str());
            let travel_class = TravelClass::from_ref(class_ref);
            // A hold whose accepted class differs from the occupancy class was
            // served as a standing substitute, which is the mode that must line
            // up with Seat Assignment's standing allocation.
            let seat_mode = match (
                hold.accepted_class_ref.as_deref(),
                hold.occupancy_class_ref.as_deref(),
            ) {
                (Some(accepted), Some(occupancy))
                    if !accepted.trim().is_empty()
                        && !occupancy.trim().is_empty()
                        && !accepted.eq_ignore_ascii_case(occupancy) =>
                {
                    SeatMode::Standing
                }
                _ => pool.seat_mode(),
            };

            match hold.state.trim().to_ascii_uppercase().as_str() {
                "REQUESTED" => {
                    let seconds = now_millis.saturating_sub(hold.requested_at_millis) as f64 / 1000.0;
                    keep_oldest(&mut ages, (HoldAgeState::Requested, travel_class), seconds);
                }
                "HELD" => {
                    // A hold past its expiry has stopped blocking, and the
                    // aggregate only clears it on the next mutation, so the
                    // liveness test is done here rather than trusted.
                    if hold.expires_at_millis > now_millis {
                        blocked += 1;
                        *units
                            .entry((CapacityUnitState::Held, travel_class, seat_mode))
                            .or_default() += 1;
                        let seconds =
                            now_millis.saturating_sub(hold.requested_at_millis) as f64 / 1000.0;
                        keep_oldest(&mut ages, (HoldAgeState::Held, travel_class), seconds);
                    }
                }
                "CONFIRMED" => {
                    blocked += 1;
                    *units
                        .entry((CapacityUnitState::Confirmed, travel_class, seat_mode))
                        .or_default() += 1;
                    let since = hold.confirmed_at_millis.unwrap_or(hold.requested_at_millis);
                    let seconds = now_millis.saturating_sub(since) as f64 / 1000.0;
                    keep_oldest(&mut ages, (HoldAgeState::Confirmed, travel_class), seconds);
                }
                // Terminal holds stay in the document until the aggregate
                // retires them, which is what bounds this count.
                "RELEASED" | "EXPIRED" | "FAILED" => {
                    *units
                        .entry((CapacityUnitState::Released, travel_class, seat_mode))
                        .or_default() += 1;
                }
                _ => {}
            }
        }
        // Sellable is a pool-level quantity, attributed to the pool's own class
        // and mode rather than to a substituted one.
        let remaining = (pool.effective_capacity() - blocked).max(0);
        *sellable
            .entry((TravelClass::from_ref(&pool.class_ref), pool.seat_mode()))
            .or_default() += remaining;
    }

    // "Occupied" is the units no new hold can take: everything blocked, whether
    // held or confirmed. It is derived from the same rows, so the two views
    // cannot disagree.
    let mut occupied: BTreeMap<(TravelClass, SeatMode), i64> = BTreeMap::new();
    for ((state, travel_class, seat_mode), held) in &units {
        if matches!(state, CapacityUnitState::Held | CapacityUnitState::Confirmed) {
            *occupied.entry((*travel_class, *seat_mode)).or_default() += held;
        }
    }
    units.extend(
        occupied
            .into_iter()
            .map(|((travel_class, seat_mode), held)| {
                (
                    (CapacityUnitState::Occupied, travel_class, seat_mode),
                    held,
                )
            }),
    );

    let mut unit_rows: Vec<CapacityUnitRow> = Vec::new();
    for ((travel_class, seat_mode), value) in sellable {
        unit_rows.push(CapacityUnitRow {
            state: CapacityUnitState::Sellable,
            travel_class,
            seat_mode,
            units: value,
        });
    }
    unit_rows.extend(units.into_iter().map(|((state, travel_class, seat_mode), units)| {
        CapacityUnitRow {
            state,
            travel_class,
            seat_mode,
            units,
        }
    }));
    unit_rows.sort_by_key(|row| {
        (
            row.state.as_str(),
            row.travel_class.as_str(),
            row.seat_mode.as_str(),
        )
    });

    let hold_age = ages
        .into_iter()
        .map(|((state, travel_class), seconds)| HoldAgeRow {
            state,
            travel_class,
            seconds,
        })
        .collect();

    CapacityMetricsSnapshot {
        units: unit_rows,
        hold_age,
    }
}

fn keep_oldest(
    ages: &mut BTreeMap<(HoldAgeState, TravelClass), f64>,
    key: (HoldAgeState, TravelClass),
    seconds: f64,
) {
    ages.entry(key)
        .and_modify(|existing| *existing = existing.max(seconds))
        .or_insert(seconds);
}

/// Holds the published level and, when metrics export is configured, the
/// observable instruments that read it.
#[derive(Clone)]
pub struct CapacityBusinessMetrics {
    snapshot: Arc<RwLock<CapacityMetricsSnapshot>>,
}

impl CapacityBusinessMetrics {
    /// Register the observable gauges against the service's OTel meter.
    ///
    /// The global meter provider is a no-op when export is not configured, so
    /// this is safe to call unconditionally and costs one `RwLock`.
    pub fn register(otel_meter: &Meter) -> Self {
        let snapshot = Arc::new(RwLock::new(CapacityMetricsSnapshot::default()));

        let units_snapshot = Arc::clone(&snapshot);
        otel_meter
            .i64_observable_gauge(CAPACITY_UNITS_METRIC)
            .with_description(
                "Capacity units by disposition, bounded travel class and seat mode.",
            )
            .with_unit("{unit}")
            .with_callback(move |observer| {
                let current = units_snapshot
                    .read()
                    .map(|snapshot| snapshot.units.clone())
                    .unwrap_or_default();
                for row in current {
                    observer.observe(
                        row.units,
                        &[
                            KeyValue::new("state", row.state.as_str()),
                            KeyValue::new("travel_class", row.travel_class.as_str()),
                            KeyValue::new("seat_mode", row.seat_mode.as_str()),
                        ],
                    );
                }
            })
            .build();

        let age_snapshot = Arc::clone(&snapshot);
        otel_meter
            .f64_observable_gauge(CAPACITY_HOLD_OLDEST_AGE_METRIC)
            .with_description("Age in seconds of the oldest nonterminal capacity hold.")
            .with_unit("s")
            .with_callback(move |observer| {
                let current = age_snapshot
                    .read()
                    .map(|snapshot| snapshot.hold_age.clone())
                    .unwrap_or_default();
                for row in current {
                    observer.observe(
                        row.seconds,
                        &[
                            KeyValue::new("state", row.state.as_str()),
                            KeyValue::new("travel_class", row.travel_class.as_str()),
                        ],
                    );
                }
            })
            .build();

        Self { snapshot }
    }

    /// Publish a freshly reconstructed level.
    pub fn record(&self, snapshot: CapacityMetricsSnapshot) {
        if let Ok(mut current) = self.snapshot.write() {
            *current = snapshot;
        }
    }

    /// The level the instruments would observe right now.
    pub fn published(&self) -> CapacityMetricsSnapshot {
        self.snapshot
            .read()
            .map(|snapshot| snapshot.clone())
            .unwrap_or_default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use opentelemetry::metrics::MeterProvider;
    use opentelemetry_sdk::metrics::InMemoryMetricExporter;
    use opentelemetry_sdk::metrics::{PeriodicReader, SdkMeterProvider};
    use std::time::Duration;

    fn pool_with_holds(
        pool_id: &str,
        class_ref: &str,
        mode: &str,
        physical: u64,
        holds: Vec<DurableHoldFact>,
    ) -> DurablePoolFact {
        DurablePoolFact {
            pool_id: pool_id.to_owned(),
            class_ref: class_ref.to_owned(),
            sellable_unit_type_ref: mode.to_owned(),
            physical_unit_count: physical,
            max_overbooking_pct: 0.0,
            holds,
        }
    }

    fn hold(
        hold_id: &str,
        state: &str,
        accepted: Option<&str>,
        occupancy: Option<&str>,
        requested_at_millis: u64,
        expires_at_millis: u64,
    ) -> DurableHoldFact {
        DurableHoldFact {
            hold_id: hold_id.to_owned(),
            state: state.to_owned(),
            requested_at_millis,
            expires_at_millis,
            confirmed_at_millis: None,
            accepted_class_ref: accepted.map(str::to_owned),
            occupancy_class_ref: occupancy.map(str::to_owned),
        }
    }

    type CollectedPoint = (String, Vec<(String, String)>);

    fn collected(
        provider: &SdkMeterProvider,
        exporter: &InMemoryMetricExporter,
    ) -> Vec<CollectedPoint> {
        use opentelemetry_sdk::metrics::data::{AggregatedMetrics, MetricData};

        let _ = provider.force_flush();
        let mut points: Vec<CollectedPoint> = Vec::new();
        for resource_metric in exporter.get_finished_metrics().expect("finished metrics") {
            for scope_metric in resource_metric.scope_metrics() {
                for metric in scope_metric.metrics() {
                    let name = metric.name().to_string();
                    match metric.data() {
                        AggregatedMetrics::I64(MetricData::Gauge(gauge)) => {
                            for point in gauge.data_points() {
                                points.push((
                                    name.clone(),
                                    point
                                        .attributes()
                                        .map(|kv| (kv.key.to_string(), kv.value.to_string()))
                                        .collect(),
                                ));
                            }
                        }
                        AggregatedMetrics::F64(MetricData::Gauge(gauge)) => {
                            for point in gauge.data_points() {
                                points.push((
                                    name.clone(),
                                    point
                                        .attributes()
                                        .map(|kv| (kv.key.to_string(), kv.value.to_string()))
                                        .collect(),
                                ));
                            }
                        }
                        _ => {}
                    }
                }
            }
        }
        points
    }

    fn provider(exporter: &InMemoryMetricExporter) -> SdkMeterProvider {
        SdkMeterProvider::builder()
            .with_reader(
                PeriodicReader::builder(exporter.clone())
                    .with_interval(Duration::from_millis(50))
                    .build(),
            )
            .build()
    }

    /// Two different business identifiers must produce the same label set. The
    /// hold, pool, service and segment references in these fixtures differ;
    /// every attribute that reaches a series must not.
    #[test]
    fn different_identifiers_produce_an_identical_label_set() {
        let first = build_snapshot(
            &[pool_with_holds(
                "pool:seg-aaaa:SECOND_CLASS",
                "SECOND_CLASS",
                "SEAT",
                10,
                vec![hold(
                    "hold-aaaa",
                    "Confirmed",
                    Some("SECOND_CLASS"),
                    Some("SECOND_CLASS"),
                    900_000,
                    1_200_000,
                )],
            )],
            1_000_000,
        );
        let second = build_snapshot(
            &[pool_with_holds(
                "pool:seg-bbbb:SECOND_CLASS",
                "SECOND_CLASS",
                "SEAT",
                10,
                vec![hold(
                    "hold-bbbb",
                    "Confirmed",
                    Some("SECOND_CLASS"),
                    Some("SECOND_CLASS"),
                    900_000,
                    1_200_000,
                )],
            )],
            1_000_000,
        );
        let labels = |snapshot: &CapacityMetricsSnapshot| -> Vec<String> {
            snapshot
                .units
                .iter()
                .map(|row| {
                    format!(
                        "{}/{}/{}",
                        row.state.as_str(),
                        row.travel_class.as_str(),
                        row.seat_mode.as_str()
                    )
                })
                .collect()
        };
        assert_eq!(labels(&first), labels(&second));
        assert!(labels(&first).len() > 1, "the fold produced no series");
    }

    #[test]
    fn a_restart_reconstructs_the_same_level_instead_of_a_reset() {
        let mut confirmed = hold(
            "hold-confirmed",
            "Confirmed",
            Some("SECOND_CLASS"),
            Some("SECOND_CLASS"),
            880_000,
            1_180_000,
        );
        confirmed.confirmed_at_millis = Some(880_000);
        let durable = vec![pool_with_holds(
            "pool:seg-aaaa:SECOND_CLASS",
            "SECOND_CLASS",
            "SEAT",
            10,
            vec![
                hold(
                    "hold-held-1",
                    "Held",
                    Some("SECOND_CLASS"),
                    Some("SECOND_CLASS"),
                    940_000,
                    1_240_000,
                ),
                hold(
                    "hold-held-2",
                    "Held",
                    Some("SECOND_CLASS"),
                    Some("SECOND_CLASS"),
                    950_000,
                    1_250_000,
                ),
                confirmed,
                hold(
                    "hold-released",
                    "Released",
                    Some("SECOND_CLASS"),
                    Some("SECOND_CLASS"),
                    700_000,
                    1_000_000,
                ),
            ],
        )];

        let before = build_snapshot(&durable, 1_000_000);
        // A second process folding the same durable document is a restart.
        let after = build_snapshot(&durable, 1_000_000);
        assert_eq!(before, after);
        assert!(
            before
                .units
                .iter()
                .any(|row| row.state == CapacityUnitState::Confirmed && row.units == 1),
            "confirmed units have to survive a restart: {:?}",
            before.units
        );
        assert!(
            before
                .units
                .iter()
                .any(|row| row.state == CapacityUnitState::Held && row.units == 2),
            "live holds have to survive a restart: {:?}",
            before.units
        );
        assert!(
            before
                .units
                .iter()
                .any(|row| row.state == CapacityUnitState::Sellable && row.units == 7),
            "sellable units are capacity minus live holds: {:?}",
            before.units
        );
        assert!(
            before.hold_age.iter().any(|row| row.state == HoldAgeState::Confirmed
                && (row.seconds - 120.0).abs() < f64::EPSILON),
            "confirmed hold age has to survive a restart: {:?}",
            before.hold_age
        );
        assert!(
            before.hold_age.iter().any(|row| row.state == HoldAgeState::Held
                && (row.seconds - 60.0).abs() < f64::EPSILON),
            "the oldest live hold sets the age: {:?}",
            before.hold_age
        );
    }

    /// A hold past its expiry has stopped blocking even though the aggregate
    /// only clears it on the next mutation. It must not be reported as held.
    #[test]
    fn an_expired_hold_is_no_longer_held() {
        let durable = vec![pool_with_holds(
            "pool:seg-aaaa:SECOND_CLASS",
            "SECOND_CLASS",
            "SEAT",
            4,
            vec![hold(
                "hold-stale",
                "Held",
                Some("SECOND_CLASS"),
                Some("SECOND_CLASS"),
                100_000,
                500_000,
            )],
        )];
        let snapshot = build_snapshot(&durable, 1_000_000);
        assert!(
            !snapshot
                .units
                .iter()
                .any(|row| row.state == CapacityUnitState::Held),
            "an expired hold still counted as held: {:?}",
            snapshot.units
        );
        assert!(
            snapshot
                .units
                .iter()
                .any(|row| row.state == CapacityUnitState::Sellable && row.units == 4),
            "expired holds return capacity to sale: {:?}",
            snapshot.units
        );
    }

    #[test]
    fn an_unexpected_class_or_mode_collapses_to_other() {
        let durable = vec![pool_with_holds(
            "pool:seg-aaaa:WEIRD",
            "WEIRD-CLASS-42",
            "MAGIC",
            4,
            vec![hold(
                "hold-weird",
                "Held",
                Some("WEIRD-CLASS-42"),
                Some("WEIRD-CLASS-42"),
                990_000,
                1_290_000,
            )],
        )];
        let snapshot = build_snapshot(&durable, 1_000_000);
        assert!(snapshot.units.iter().all(|row| {
            row.travel_class == TravelClass::Other && row.seat_mode == SeatMode::Other
        }));
    }

    /// A standing substitution is the case the capacity/seat conservation check
    /// exists for: the customer bought `SECOND_CLASS`, the unit is standing.
    #[test]
    fn a_standing_substitution_reports_the_accepted_class_and_standing_mode() {
        let durable = vec![pool_with_holds(
            "pool:seg-aaaa:STANDING",
            "STANDING",
            "STANDING_QUOTA",
            3,
            vec![
                hold(
                    "hold-standing-1",
                    "Confirmed",
                    Some("SECOND_CLASS"),
                    Some("STANDING"),
                    900_000,
                    1_200_000,
                ),
                hold(
                    "hold-standing-2",
                    "Confirmed",
                    Some("SECOND_CLASS"),
                    Some("STANDING"),
                    900_000,
                    1_200_000,
                ),
            ],
        )];
        let snapshot = build_snapshot(&durable, 1_000_000);
        let confirmed = snapshot
            .units
            .iter()
            .find(|row| row.state == CapacityUnitState::Confirmed)
            .expect("a confirmed row");
        assert_eq!(confirmed.travel_class, TravelClass::SecondClass);
        assert_eq!(confirmed.seat_mode, SeatMode::Standing);
        assert_eq!(confirmed.units, 2);
    }

    /// The observed attributes are read back from the SDK here, so a hold or
    /// pool identifier that leaked into a label would fail rather than merely
    /// look odd.
    #[test]
    fn emitted_attributes_are_only_the_closed_enums() {
        let exporter = InMemoryMetricExporter::default();
        let provider = provider(&exporter);
        let metrics =
            CapacityBusinessMetrics::register(&provider.meter("capacity-availability-test"));

        let durable = vec![
            pool_with_holds(
                "pool:seg-aaaa:SECOND_CLASS",
                "SECOND_CLASS",
                "SEAT",
                10,
                vec![hold(
                    "hold-aaaa",
                    "Held",
                    Some("SECOND_CLASS"),
                    Some("SECOND_CLASS"),
                    990_000,
                    1_290_000,
                )],
            ),
            pool_with_holds(
                "pool:seg-bbbb:SECOND_CLASS",
                "SECOND_CLASS",
                "SEAT",
                10,
                vec![hold(
                    "hold-bbbb",
                    "Held",
                    Some("SECOND_CLASS"),
                    Some("SECOND_CLASS"),
                    990_000,
                    1_290_000,
                )],
            ),
        ];
        metrics.record(build_snapshot(&durable, 1_000_000));

        let points = collected(&provider, &exporter);
        let held: Vec<_> = points
            .iter()
            .filter(|(name, attributes)| {
                name == CAPACITY_UNITS_METRIC
                    && attributes
                        .iter()
                        .any(|(key, value)| key == "state" && value == "held")
            })
            .collect();
        assert_eq!(held.len(), 1, "two pools share one held series: {held:?}");
        let mut attributes = held[0].1.clone();
        attributes.sort();
        assert_eq!(
            attributes,
            vec![
                ("seat_mode".to_string(), "seat".to_string()),
                ("state".to_string(), "held".to_string()),
                ("travel_class".to_string(), "second_class".to_string()),
            ]
        );
        for (_, attributes) in points {
            for (key, value) in attributes {
                assert!(
                    ["state", "travel_class", "seat_mode"].contains(&key.as_str()),
                    "unexpected attribute {key}"
                );
                assert!(
                    !value.contains("seg-aaaa")
                        && !value.contains("seg-bbbb")
                        && !value.contains("pool:")
                        && !value.contains("hold-"),
                    "an identifier reached a label: {value}"
                );
            }
        }
    }
}
