//! Low-cardinality ticketing telemetry.
//!
//! Contract: `docs/07-observability/business-metrics.md`. Two raw instruments
//! leave this service:
//!
//! * `train_ticket.ticketing.transitions` (counter) by `stage`, `outcome`,
//!   `ticket_type`, `reason_code`;
//! * `train_ticket.ticketing.active` (up-down counter) by `stage`, `ticket_type`.
//!
//! The counter is recorded where the decision is made. The level is not: it is
//! read back from `entitlement_snapshots`, because an in-process counter would
//! start at zero after a restart and publish "no live tickets" for a system full
//! of them.
//!
//! `reason_code` is an owned vocabulary with an explicit `other` bucket.
//! Exception text is never copied into it: a message is unbounded, and it is the
//! one field where upstream failure detail would otherwise leak into a label.
//! No entitlement, order, booking, traveler, credential, segment or correlation
//! reference is a label either.

use std::collections::BTreeMap;
use std::sync::{Arc, RwLock};

use opentelemetry::KeyValue;
use opentelemetry::metrics::Meter;

pub const TICKETING_TRANSITIONS_METRIC: &str = "train_ticket.ticketing.transitions";
pub const TICKETING_ACTIVE_METRIC: &str = "train_ticket.ticketing.active";

/// The ticketing stage a transition belongs to.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum TicketStage {
    Issue,
    Replacement,
    Void,
    Suspend,
    Resume,
    Expire,
    /// Boarding, check-in and no-show facts reported by Fulfillment.
    Validation,
}

impl TicketStage {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Issue => "issue",
            Self::Replacement => "replacement",
            Self::Void => "void",
            Self::Suspend => "suspend",
            Self::Resume => "resume",
            Self::Expire => "expire",
            Self::Validation => "validation",
        }
    }

    /// Issue purpose decides whether an issuance is a first issue or a
    /// replacement; the credential path is otherwise identical.
    pub fn from_issue_purpose(purpose: &str) -> Self {
        match purpose.trim().to_ascii_uppercase().as_str() {
            "REPLACEMENT" | "DISRUPTION_REPLACEMENT" | "MANUAL_RECOVERY" | "PROVIDER_REBUILD" => {
                Self::Replacement
            }
            _ => Self::Issue,
        }
    }
}

/// How a transition ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum TicketOutcome {
    Accepted,
    Rejected,
    /// Replayed or already in the target state: nothing changed, and nothing is
    /// wrong.
    Noop,
    Failed,
}

impl TicketOutcome {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Accepted => "accepted",
            Self::Rejected => "rejected",
            Self::Noop => "noop",
            Self::Failed => "failed",
        }
    }
}

/// Bounded ticket-type vocabulary. Anything else is `unknown`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum TicketType {
    ETicket,
    PaperTicket,
    PickupCode,
    BoardingPass,
    FerryTicket,
    CoachETicket,
    RideCode,
    Unknown,
}

impl TicketType {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::ETicket => "eticket",
            Self::PaperTicket => "paper_ticket",
            Self::PickupCode => "pickup_code",
            Self::BoardingPass => "boarding_pass",
            Self::FerryTicket => "ferry_ticket",
            Self::CoachETicket => "coach_eticket",
            Self::RideCode => "ride_code",
            Self::Unknown => "unknown",
        }
    }

    pub fn from_ref(value: &str) -> Self {
        match value.trim().to_ascii_uppercase().as_str() {
            "ETICKET" | "E_TICKET" => Self::ETicket,
            "PAPERTICKET" | "PAPER_TICKET" => Self::PaperTicket,
            "PICKUPCODE" | "PICKUP_CODE" => Self::PickupCode,
            "BOARDINGPASS" | "BOARDING_PASS" => Self::BoardingPass,
            "FERRYTICKET" | "FERRY_TICKET" => Self::FerryTicket,
            "COACHETICKET" | "COACH_E_TICKET" => Self::CoachETicket,
            "RIDECODE" | "RIDE_CODE" => Self::RideCode,
            _ => Self::Unknown,
        }
    }
}

/// The owned `reason_code` vocabulary. `Other` is the only bucket an unmapped
/// condition may fall into.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum TicketReasonCode {
    None,
    PreconditionsUnmet,
    InvalidRequest,
    TerminalStatus,
    DuplicateIssue,
    IssueFailureNotRetryable,
    Frozen,
    VoidBlockedByUse,
    ExceptionalRuleRequired,
    AlreadySuspended,
    NotSuspended,
    IdempotentReplay,
    NotFound,
    Unavailable,
    Other,
}

impl TicketReasonCode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::PreconditionsUnmet => "preconditions_unmet",
            Self::InvalidRequest => "invalid_request",
            Self::TerminalStatus => "terminal_status",
            Self::DuplicateIssue => "duplicate_issue",
            Self::IssueFailureNotRetryable => "issue_failure_not_retryable",
            Self::Frozen => "frozen",
            Self::VoidBlockedByUse => "void_blocked_by_use",
            Self::ExceptionalRuleRequired => "exceptional_rule_required",
            Self::AlreadySuspended => "already_suspended",
            Self::NotSuspended => "not_suspended",
            Self::IdempotentReplay => "idempotent_replay",
            Self::NotFound => "not_found",
            Self::Unavailable => "unavailable",
            Self::Other => "other",
        }
    }
}

/// One transition to record.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TicketingTransition {
    pub stage: TicketStage,
    pub outcome: TicketOutcome,
    pub ticket_type: TicketType,
    pub reason_code: TicketReasonCode,
}

/// Where a nonterminal entitlement currently sits.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum TicketingActiveStage {
    /// Waiting to be issued: requested, or a retryable issue failure.
    Issue,
    /// Issued and not yet terminal. The credential is live.
    Issued,
    /// Frozen by a business case and awaiting a decision.
    Suspended,
}

impl TicketingActiveStage {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Issue => "issue",
            Self::Issued => "issued",
            Self::Suspended => "suspended",
        }
    }
}

/// One published series of the `active` level: closed enums only.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct TicketingActiveRow {
    pub stage: TicketingActiveStage,
    pub ticket_type: TicketType,
    pub tickets: i64,
}

/// One durable bucket of `entitlement_snapshots` read for the level.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DurableEntitlementBucket {
    /// `status.kind` as it is stored, for example `REQUESTED` or `ISSUE_FAILED`.
    pub status_kind: String,
    /// `status.retryable` when the status carries it.
    pub retryable: Option<bool>,
    /// `credentialRef.credentialType` when a credential exists yet.
    pub credential_type: Option<String>,
    pub tickets: i64,
}

/// Fold durable entitlement buckets into the published level.
///
/// Pure, so the restart test can fold the same durable rows twice and prove the
/// level repeats rather than resetting. Terminal statuses (voided, expired,
/// no-show) are deliberately absent: they are not in progress.
pub fn build_active_rows(buckets: &[DurableEntitlementBucket]) -> Vec<TicketingActiveRow> {
    let mut rows: BTreeMap<(TicketingActiveStage, TicketType), i64> = BTreeMap::new();
    for bucket in buckets {
        let ticket_type = bucket
            .credential_type
            .as_deref()
            .map(TicketType::from_ref)
            .unwrap_or(TicketType::Unknown);
        let stage = match bucket.status_kind.trim().to_ascii_uppercase().as_str() {
            "REQUESTED" => TicketingActiveStage::Issue,
            "ISSUE_FAILED" if bucket.retryable.unwrap_or(false) => TicketingActiveStage::Issue,
            "ISSUED" => TicketingActiveStage::Issued,
            "SUSPENDED" => TicketingActiveStage::Suspended,
            _ => continue,
        };
        *rows.entry((stage, ticket_type)).or_default() += bucket.tickets.max(0);
    }
    rows.into_iter()
        .filter(|(_, tickets)| *tickets > 0)
        .map(|((stage, ticket_type), tickets)| TicketingActiveRow {
            stage,
            ticket_type,
            tickets,
        })
        .collect()
}

/// Holds the published level and the recorders that read it.
#[derive(Clone)]
pub struct TicketingMetrics {
    counter: Option<Arc<dyn TransitionSink>>,
    active: Arc<RwLock<Vec<TicketingActiveRow>>>,
}

impl TicketingMetrics {
    /// Register both instruments against the service's OTel meter.
    pub fn register(otel_meter: &Meter) -> Self {
        let transitions = otel_meter
            .u64_counter(TICKETING_TRANSITIONS_METRIC)
            .with_description(
                "Ticket issue, replacement, void and validation outcomes by bounded reason.",
            )
            .with_unit("{transition}")
            .build();
        let counter: Arc<dyn TransitionSink> = Arc::new(OpenTelemetryTransitionSink { transitions });

        let active = Arc::new(RwLock::new(Vec::<TicketingActiveRow>::new()));
        let active_read = Arc::clone(&active);
        otel_meter
            .i64_observable_up_down_counter(TICKETING_ACTIVE_METRIC)
            .with_description("Ticketing work currently in progress, by stage and ticket type.")
            .with_unit("{ticket}")
            .with_callback(move |observer| {
                let rows = active_read
                    .read()
                    .map(|rows| rows.clone())
                    .unwrap_or_default();
                for row in rows {
                    observer.observe(
                        row.tickets,
                        &[
                            KeyValue::new("stage", row.stage.as_str()),
                            KeyValue::new("ticket_type", row.ticket_type.as_str()),
                        ],
                    );
                }
            })
            .build();

        Self {
            counter: Some(counter),
            active,
        }
    }

    /// A recorder that keeps the levels but publishes nothing, for tests and for
    /// call sites that have no meter.
    pub fn disabled() -> Self {
        Self {
            counter: None,
            active: Arc::new(RwLock::new(Vec::new())),
        }
    }

    pub fn transition(&self, transition: TicketingTransition) {
        if let Some(counter) = &self.counter {
            counter.record(transition);
        }
    }

    /// Publish a freshly reconstructed `active` level.
    pub fn publish_active(&self, rows: Vec<TicketingActiveRow>) {
        if let Ok(mut current) = self.active.write() {
            *current = rows;
        }
    }

    /// The level the instrument would observe right now.
    pub fn published_active(&self) -> Vec<TicketingActiveRow> {
        self.active
            .read()
            .map(|rows| rows.clone())
            .unwrap_or_default()
    }
}

trait TransitionSink: Send + Sync {
    fn record(&self, transition: TicketingTransition);
}

struct OpenTelemetryTransitionSink {
    transitions: opentelemetry::metrics::Counter<u64>,
}

impl TransitionSink for OpenTelemetryTransitionSink {
    fn record(&self, transition: TicketingTransition) {
        self.transitions.add(
            1,
            &[
                KeyValue::new("stage", transition.stage.as_str()),
                KeyValue::new("outcome", transition.outcome.as_str()),
                KeyValue::new("ticket_type", transition.ticket_type.as_str()),
                KeyValue::new("reason_code", transition.reason_code.as_str()),
            ],
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use opentelemetry::metrics::MeterProvider;
    use opentelemetry_sdk::metrics::data::{AggregatedMetrics, MetricData};
    use opentelemetry_sdk::metrics::{InMemoryMetricExporter, PeriodicReader, SdkMeterProvider};
    use std::time::Duration;

    fn bucket(status: &str, retryable: Option<bool>, credential: Option<&str>, tickets: i64) -> DurableEntitlementBucket {
        DurableEntitlementBucket {
            status_kind: status.to_owned(),
            retryable,
            credential_type: credential.map(str::to_owned),
            tickets,
        }
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

    fn tls(exporter: &InMemoryMetricExporter, provider: &SdkMeterProvider) -> Vec<(String, Vec<(String, String)>)> {
        let _ = provider.force_flush();
        let mut points = Vec::new();
        for resource_metric in exporter.get_finished_metrics().expect("finished metrics") {
            for scope_metric in resource_metric.scope_metrics() {
                for metric in scope_metric.metrics() {
                    let name = metric.name().to_string();
                    match metric.data() {
                        AggregatedMetrics::I64(MetricData::Sum(sum)) => {
                            for point in sum.data_points() {
                                points.push((
                                    name.clone(),
                                    point
                                        .attributes()
                                        .map(|kv| (kv.key.to_string(), kv.value.to_string()))
                                        .collect::<Vec<_>>(),
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
                                        .collect::<Vec<_>>(),
                                ));
                            }
                        }
                        AggregatedMetrics::I64(MetricData::Gauge(gauge)) => {
                            for point in gauge.data_points() {
                                points.push((
                                    name.clone(),
                                    point
                                        .attributes()
                                        .map(|kv| (kv.key.to_string(), kv.value.to_string()))
                                        .collect::<Vec<_>>(),
                                ));
                            }
                        }
                        AggregatedMetrics::U64(MetricData::Sum(sum)) => {
                            for point in sum.data_points() {
                                points.push((
                                    name.clone(),
                                    point
                                        .attributes()
                                        .map(|kv| (kv.key.to_string(), kv.value.to_string()))
                                        .collect::<Vec<_>>(),
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

    /// Two different entitlements must produce the same label set.
    #[test]
    fn different_identifiers_produce_an_identical_label_set() {
        let exporter = InMemoryMetricExporter::default();
        let provider = provider(&exporter);
        let metrics = TicketingMetrics::register(&provider.meter("entitlement-ticketing-test"));

        let transition = TicketingTransition {
            stage: TicketStage::Issue,
            outcome: TicketOutcome::Accepted,
            ticket_type: TicketType::ETicket,
            reason_code: TicketReasonCode::None,
        };
        // Two calls, as two different entitlements would produce them.
        metrics.transition(transition);
        metrics.transition(transition);

        let points = tls(&exporter, &provider);
        let transitions: Vec<_> = points
            .iter()
            .filter(|(name, _)| name == TICKETING_TRANSITIONS_METRIC)
            .collect();
        assert_eq!(transitions.len(), 1, "two entitlements share one series");
        for (_, attributes) in transitions {
            for (key, value) in attributes {
                assert!(
                    ["stage", "outcome", "ticket_type", "reason_code"].contains(&key.as_str()),
                    "unexpected attribute {key}"
                );
                assert!(
                    !value.starts_with("ent-") && !value.starts_with("ord-"),
                    "an identifier reached a label: {value}"
                );
            }
        }
    }

    #[test]
    fn a_restart_reconstructs_the_same_active_level_instead_of_a_reset() {
        let durable = vec![
            bucket("REQUESTED", None, None, 4),
            bucket("ISSUE_FAILED", Some(true), None, 2),
            bucket("ISSUE_FAILED", Some(false), None, 9),
            bucket("ISSUED", None, Some("ETICKET"), 30),
            bucket("SUSPENDED", None, Some("E_TICKET"), 3),
            bucket("VOIDED", None, Some("ETICKET"), 100),
        ];
        let before = build_active_rows(&durable);
        let after = build_active_rows(&durable);
        assert_eq!(before, after);
        assert_eq!(
            before
                .iter()
                .find(|row| row.stage == TicketingActiveStage::Issue)
                .map(|row| row.tickets),
            Some(6),
            "in-flight issuance survives a restart: {before:?}"
        );
        assert!(
            !before.iter().any(|row| row.tickets == 100),
            "a terminal status must not count as active: {before:?}"
        );
    }

    #[test]
    fn only_the_owned_reason_vocabulary_reaches_a_label() {
        let exporter = InMemoryMetricExporter::default();
        let provider = provider(&exporter);
        let metrics = TicketingMetrics::register(&provider.meter("entitlement-ticketing-test"));
        for reason_code in [
            TicketReasonCode::None,
            TicketReasonCode::TerminalStatus,
            TicketReasonCode::VoidBlockedByUse,
            TicketReasonCode::Other,
        ] {
            metrics.transition(TicketingTransition {
                stage: TicketStage::Void,
                outcome: TicketOutcome::Rejected,
                ticket_type: TicketType::Unknown,
                reason_code,
            });
        }
        let values: Vec<String> = tls(&exporter, &provider)
            .into_iter()
            .filter(|(name, _)| name == TICKETING_TRANSITIONS_METRIC)
            .flat_map(|(_, attributes)| attributes)
            .filter(|(key, _)| key == "reason_code")
            .map(|(_, value)| value)
            .collect();
        for value in &values {
            assert!(
                !value.contains(' ') && !value.contains(':'),
                "reason codes are owned tokens, not messages: {value}"
            );
        }
        assert!(values.contains(&"other".to_string()));
    }

    /// The `active` level is read back through the SDK too, so a status string
    /// or a credential number in a label would fail here.
    #[test]
    fn active_level_attributes_are_only_stage_and_ticket_type() {
        let exporter = InMemoryMetricExporter::default();
        let provider = provider(&exporter);
        let metrics = TicketingMetrics::register(&provider.meter("entitlement-ticketing-test"));
        metrics.publish_active(build_active_rows(&[
            bucket("REQUESTED", None, None, 2),
            bucket("ISSUED", None, Some("ETICKET"), 7),
        ]));

        let points: Vec<_> = tls(&exporter, &provider)
            .into_iter()
            .filter(|(name, _)| name == TICKETING_ACTIVE_METRIC)
            .collect();
        assert_eq!(points.len(), 2, "two stages have two series: {points:?}");
        for (_, attributes) in points {
            for (key, value) in attributes {
                assert!(
                    ["stage", "ticket_type"].contains(&key.as_str()),
                    "unexpected attribute {key}"
                );
                assert!(
                    !value.starts_with("ent-") && !value.starts_with("ETK-"),
                    "an identifier reached a label: {value}"
                );
            }
        }
    }
}
