from __future__ import annotations

import hashlib
import logging
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from decimal import Decimal
from typing import Any

from reporting.ids import prefixed_uuid7
from reporting.domain import (
    AnomalyDetected,
    AnomalyDetector,
    ConsumedEventLog,
    ContextCountReport,
    ConsumedEventRecord,
    DashboardReadModel,
    MetricAggregator,
    MetricCategory,
    MetricDefinition,
    MetricGranularity,
    MetricRef,
    MetricSnapshot,
    MetricStatus,
    Money,
    OperationalEvent,
    ReadModelSnapshot,
    ReadModelStatus,
    RevenueReport,
    RouteMetrics,
)

from .ports import EventEnvelope, EventPublisher, HandlerResult

PRODUCER = "reporting"
logger = logging.getLogger(__name__)

REVENUE_DASHBOARD_SOURCE_EVENTS = (
    "PaymentCaptured",
    "PaymentSucceeded",
    "RefundSettled",
    "RefundCompleted",
    "RefundIssued",
    "RevenueRecognized",
    "RevenueRecognitionReversed",
    "ReconciliationCompleted",
    "InvoiceGenerated",
    "JourneyOrderConfirmed",
    "BoardingVerified",
    "FulfillmentCompleted",
    "NoShowRecorded",
)

_REVENUE_RELEVANT_EVENT_TYPES = frozenset(REVENUE_DASHBOARD_SOURCE_EVENTS)

_REPORTING_APPLIED_EVENT_TYPES = frozenset(
    REVENUE_DASHBOARD_SOURCE_EVENTS
    + (
        "TripSearched",
        "SearchPerformed",
        "OfferSearchRequested",
        "JourneyOrderCreated",
        "OrderCreated",
        "OrderConfirmed",
        "BookingConfirmed",
        "PaymentFailed",
        "PaymentDeclined",
        "PaymentCaptureFailed",
        "CapacityUpdated",
        "SeatInventoryUpdated",
        "CapacityExhausted",
        "ScalperBlocked",
        "RiskBookingBlocked",
        "SupportCaseOpened",
        "WaitlistQueued",
        "WaitlistExpired",
        "DispatchRequested",
        "DispatchFailed",
        "AncillaryQuoted",
        "AncillaryOrderItemRefunded",
        "InsurancePolicyIssued",
        "ChannelOrderFailed",
        "ChannelRefundFailed",
        "ChannelRefundSucceeded",
    )
)


def reporting_applies_event_type(event_type: str) -> bool:
    """Return whether Reporting has a projection rule for an upstream event.

    The production subscriber is attached to broad context streams. Facts that
    are not modeled by Reporting must be acknowledged as no-ops rather than
    parsed into generic projections where payload shape mismatches can poison
    the consumer group and eventually DLQ the message.
    """
    return event_type in _REPORTING_APPLIED_EVENT_TYPES


def dashboard_consumes_event(dashboard: DashboardReadModel, event_type: str) -> bool:
    if dashboard.dashboard_id == "dash-revenue" and event_type in _REVENUE_RELEVANT_EVENT_TYPES:
        return True
    return not dashboard.source_events or event_type in dashboard.source_events


def utc_now() -> datetime:
    return datetime.now(UTC)


def rfc3339_utc(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


@dataclass(slots=True)
class RebuildRun:
    rebuild_id: str
    dashboard_id: str
    rebuilt_at: datetime
    status: str
    event_count: int
    digest: str


@dataclass(slots=True)
class ReportingReadRepository:
    metrics: dict[str, MetricDefinition] = field(default_factory=dict)
    dashboards: dict[str, DashboardReadModel] = field(default_factory=dict)
    rebuild_runs: dict[str, list[RebuildRun]] = field(default_factory=dict)
    consumed_events: ConsumedEventLog = field(default_factory=lambda: ConsumedEventLog("cel-reporting"))
    metric_aggregator: MetricAggregator = field(default_factory=MetricAggregator)
    anomaly_detector: AnomalyDetector = field(default_factory=AnomalyDetector)

    def list_metrics(self, *, category: MetricCategory | None, limit: int, offset: int) -> tuple[list[MetricDefinition], int]:
        values = list(self.metrics.values())
        if category is not None:
            values = [metric for metric in values if metric.category is category]
        values.sort(key=lambda metric: metric.metric_id)
        return values[offset : offset + limit], len(values)

    def get_metric(self, metric_id: str) -> MetricDefinition | None:
        return self.metrics.get(metric_id)

    def get_dashboard(self, dashboard_id: str) -> DashboardReadModel | None:
        return self.dashboards.get(dashboard_id)

    def list_rebuilds(self, dashboard_id: str, *, limit: int, offset: int) -> tuple[list[RebuildRun], int]:
        values = list(self.rebuild_runs.get(dashboard_id, ()))
        values.sort(key=lambda run: run.rebuilt_at, reverse=True)
        return values[offset : offset + limit], len(values)

    def record_consumed_event(self, envelope: EventEnvelope) -> bool:
        if self.consumed_events.has_consumed(envelope.eventId):
            return False
        self.consumed_events = self.consumed_events.record(
            ConsumedEventRecord(
                event_id=envelope.eventId,
                consumed_at=utc_now(),
                source=envelope.producer,
                event_type=envelope.eventType,
            )
        )
        normalized = operational_event_from_envelope(envelope)
        self.metric_aggregator.record(normalized)
        for dashboard_id, dashboard in list(self.dashboards.items()):
            if dashboard_consumes_event(dashboard, envelope.eventType):
                self.dashboards[dashboard_id] = dashboard.mark_stale()
        return True

    def operational_metrics(self, at: datetime | None = None) -> MetricSnapshot:
        return self.metric_aggregator.snapshot(at)

    def route_metrics(self, at: datetime | None = None) -> tuple[RouteMetrics, ...]:
        return self.metric_aggregator.route_metrics(at)

    def context_count_report(self, group_by: str = "source_context", limit: int = 20, at: datetime | None = None) -> ContextCountReport:
        return self.metric_aggregator.context_count_report(group_by=group_by, limit=limit, at=at)

    def revenue_report(self, group_by: str = "route", limit: int = 20, at: datetime | None = None) -> RevenueReport:
        return self.metric_aggregator.revenue_report(group_by=group_by, limit=limit, at=at)

    def detect_anomalies(self, at: datetime | None = None) -> tuple[AnomalyDetected, ...]:
        return self.anomaly_detector.evaluate(self.metric_aggregator, at)

    def list_anomalies(self) -> tuple[AnomalyDetected, ...]:
        return self.anomaly_detector.list_active()


def _payload_value(payload: Mapping[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in payload:
            return payload[key]
    return None


def _parse_occurred_at(value: str) -> datetime:
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)
    except ValueError:
        return utc_now()


def _parse_money(payload: Mapping[str, Any]) -> Money | None:
    raw_amount = _payload_value(payload, "amount", "capturedAmount", "revenue", "refundAmount", "price")
    if isinstance(raw_amount, Mapping):
        amount_value = _payload_value(raw_amount, "amount", "value", "minorUnits")
        currency = str(_payload_value(raw_amount, "currency", "currencyCode") or _payload_value(payload, "currency", "currencyCode") or "USD")
        if "minorUnits" in raw_amount and "amount" not in raw_amount and "value" not in raw_amount:
            amount_value = Decimal(str(amount_value)) / Decimal("100")
    else:
        amount_value = raw_amount
        currency = str(_payload_value(payload, "currency", "currencyCode") or "USD")
    if amount_value is None:
        return None
    return Money(amount_value, currency)


def _parse_date(value: Any) -> date | None:
    if value is None:
        return None
    if isinstance(value, date) and not isinstance(value, datetime):
        return value
    if isinstance(value, datetime):
        return value.date()
    try:
        return date.fromisoformat(str(value)[:10])
    except ValueError:
        return None


def _parse_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _parse_decimal(value: Any) -> Decimal | None:
    if value is None:
        return None
    try:
        return Decimal(str(value))
    except Exception:
        return None


def _parse_bool(value: Any) -> bool:
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "y"}
    return bool(value)


_FAILURE_EVENT_WORDS = (
    "Failed",
    "Rejected",
    "Missed",
    "Expired",
    "Cancelled",
    "Revoked",
    "Blocked",
    "Discrepancy",
    "NoShow",
    "Degradation",
)


def _is_anomaly_signal(event_type: str, payload: Mapping[str, Any]) -> bool:
    if any(word in event_type for word in _FAILURE_EVENT_WORDS):
        return True
    status = str(_payload_value(payload, "status", "finalStatus", "result", "outcome") or "").upper()
    return status in {"FAILED", "REJECTED", "MISSED", "EXPIRED", "CANCELLED", "REVOKED", "BLOCKED"}


def operational_event_from_envelope(envelope: EventEnvelope) -> OperationalEvent:
    payload = envelope.payload or {}
    anomaly_signal = _is_anomaly_signal(envelope.eventType, payload)
    return OperationalEvent(
        event_id=envelope.eventId,
        event_type=envelope.eventType,
        occurred_at=_parse_occurred_at(envelope.occurredAt),
        route_id=_payload_value(payload, "routeId", "route_id", "route", "segmentRef", "serviceSegmentRef"),
        service_date=_parse_date(_payload_value(payload, "serviceDate", "travelDate", "departureDate", "requestedAt", "validFrom")),
        seat_class=_payload_value(payload, "seatClass", "seat_class", "class", "berthType"),
        amount=_parse_money(payload),
        channel=_payload_value(payload, "channel", "salesChannel", "channelId", "sourceChannel"),
        passenger_type=_payload_value(payload, "passengerType", "passenger_type", "travelerType"),
        capacity=_parse_int(_payload_value(payload, "capacity", "totalCapacity", "seatsAvailable", "availableSeats", "availableUnits")),
        confirmed=_parse_int(_payload_value(payload, "confirmed", "confirmedSeats", "bookedSeats", "usedSeats", "allocatedSeats")),
        booking_latency_ms=_parse_int(_payload_value(payload, "bookingLatencyMs", "latencyMs", "elapsedMs")),
        payment_failed=_parse_bool(_payload_value(payload, "paymentFailed", "failed", "declined") or envelope.eventType in {"PaymentFailed", "PaymentDeclined", "PaymentCaptureFailed", "ChannelOrderFailed", "ChannelRefundFailed"}),
        refunded=_parse_bool(_payload_value(payload, "refunded") or envelope.eventType in {"RefundSettled", "RefundCompleted", "RefundIssued", "RevenueRecognitionReversed", "ChannelRefundSucceeded", "AncillaryOrderItemRefunded"}),
        scalper_blocked=_parse_bool(_payload_value(payload, "scalperBlocked", "blockedByRisk") or envelope.eventType in {"ScalperBlocked", "RiskBookingBlocked", "RiskBlockApplied"}),
        distance_km=_parse_decimal(_payload_value(payload, "distanceKm", "distance_km")),
        ancillary_attached=_parse_bool(_payload_value(payload, "ancillaryAttached", "ancillary_attach") or envelope.eventType.startswith("Ancillary")),
        insurance_attached=_parse_bool(_payload_value(payload, "insuranceAttached", "insurance_attach") or envelope.eventType.startswith("Insurance")),
        source_context=envelope.producer,
        anomaly_signal=anomaly_signal,
    )


def default_repository() -> ReportingReadRepository:
    published_at = datetime(2026, 7, 5, 10, 30, tzinfo=UTC)
    revenue_metric = MetricDefinition(
        metric_id="metric-revenue",
        name="Total Revenue",
        description="Sum of captured payments by reporting period.",
        owner="finance-team",
        category=MetricCategory.FINANCIAL,
        granularity=MetricGranularity.DAILY,
        version="1.0.0",
        expression="SUM(payment.capturedAmount.minorUnits)",
        status=MetricStatus.PUBLISHED,
        published_at=published_at,
        source_lineage=REVENUE_DASHBOARD_SOURCE_EVENTS,
    )
    support_metric = MetricDefinition(
        metric_id="metric-support-cases",
        name="Support Cases Opened",
        description="Count of customer-service support cases opened.",
        owner="customer-service-team",
        category=MetricCategory.CUSTOMER_SERVICE,
        granularity=MetricGranularity.DAILY,
        version="1.0.0",
        expression="COUNT(SupportCaseOpened)",
        status=MetricStatus.PUBLISHED,
        published_at=published_at,
        source_lineage=("SupportCaseOpened",),
    )
    snapshot = ReadModelSnapshot(
        rebuild_id="rebuild-revenue-001",
        rebuilt_at=published_at,
        metrics=(MetricRef("metric-revenue", "1.0.0"),),
        event_count=128,
        digest="sha256-revenue-001",
    )
    dashboard = DashboardReadModel(
        dashboard_id="dash-revenue",
        name="Revenue Dashboard",
        description="Revenue and settlement dashboard.",
        metrics=(MetricRef("metric-revenue", "1.0.0"),),
        current_snapshot=snapshot,
        last_built_at=published_at,
        source_events=REVENUE_DASHBOARD_SOURCE_EVENTS,
    )
    return ReportingReadRepository(
        metrics={revenue_metric.metric_id: revenue_metric, support_metric.metric_id: support_metric},
        dashboards={dashboard.dashboard_id: dashboard},
        rebuild_runs={
            dashboard.dashboard_id: [
                RebuildRun(
                    rebuild_id=snapshot.rebuild_id,
                    dashboard_id=dashboard.dashboard_id,
                    rebuilt_at=snapshot.rebuilt_at,
                    status="COMPLETED",
                    event_count=snapshot.event_count,
                    digest=snapshot.digest,
                )
            ]
        },
    )


class ReportingApplicationService:
    def __init__(self, repository: ReportingReadRepository | None = None, publisher: EventPublisher | None = None) -> None:
        self.repository = repository or default_repository()
        self.publisher = publisher

    def list_metrics(self, *, category: MetricCategory | None, limit: int, offset: int) -> tuple[list[MetricDefinition], int]:
        return self.repository.list_metrics(category=category, limit=limit, offset=offset)

    def get_metric(self, metric_id: str) -> MetricDefinition | None:
        return self.repository.get_metric(metric_id)

    def get_dashboard(self, dashboard_id: str) -> DashboardReadModel | None:
        return self.repository.get_dashboard(dashboard_id)

    def list_rebuilds(self, dashboard_id: str, *, limit: int, offset: int) -> tuple[list[RebuildRun], int]:
        return self.repository.list_rebuilds(dashboard_id, limit=limit, offset=offset)

    def operational_metrics(self, at: datetime | None = None) -> MetricSnapshot:
        return self.repository.operational_metrics(at)

    def route_metrics(self, at: datetime | None = None) -> tuple[RouteMetrics, ...]:
        return self.repository.route_metrics(at)

    def context_count_report(self, group_by: str = "source_context", limit: int = 20, at: datetime | None = None) -> ContextCountReport:
        return self.repository.context_count_report(group_by=group_by, limit=limit, at=at)

    def revenue_report(self, group_by: str = "route", limit: int = 20, at: datetime | None = None) -> RevenueReport:
        return self.repository.revenue_report(group_by=group_by, limit=limit, at=at)

    def list_anomalies(self) -> tuple[AnomalyDetected, ...]:
        return self.repository.list_anomalies()

    def trends(self) -> dict[str, object]:
        snapshot = self.operational_metrics()
        return {
            "generatedAt": rfc3339_utc(snapshot.generated_at),
            "series": [
                {"metric": "orders_per_second", "points": [{"at": rfc3339_utc(snapshot.generated_at), "value": snapshot.orders_per_second}]},
                {"metric": "revenue_per_hour", "points": [{"at": rfc3339_utc(snapshot.generated_at), "value": str(snapshot.revenue_per_hour.amount)}]},
                {"metric": "refund_rate", "points": [{"at": rfc3339_utc(snapshot.generated_at), "value": snapshot.refund_rate}]},
            ],
        }

    def handle_event(self, envelope: EventEnvelope) -> HandlerResult:
        if not reporting_applies_event_type(envelope.eventType):
            return HandlerResult.success()
        try:
            if self.repository.record_consumed_event(envelope):
                self._publish_detected_anomalies(envelope)
                self._rebuild_stale_dashboards(envelope)
        except Exception as exc:  # pragma: no cover - defensive boundary for broker callback
            return HandlerResult.transient_error(str(exc))
        return HandlerResult.success()

    def _publish_detected_anomalies(self, envelope: EventEnvelope) -> None:
        for anomaly in self.repository.detect_anomalies():
            self._log_anomaly_alert(anomaly)
            self.publish_domain_event(
                event_type="AnomalyDetected",
                payload={
                    "ruleId": anomaly.rule_id,
                    "currentValue": str(anomaly.current_value),
                    "threshold": str(anomaly.threshold),
                    "severity": anomaly.severity.value,
                    "detectedAt": rfc3339_utc(anomaly.detected_at),
                },
                correlation_id=envelope.correlationId,
                causation_id=envelope.eventId,
                occurred_at=anomaly.detected_at,
            )
            if anomaly.urgent:
                self.publish_domain_event(
                    event_type="UrgentNotificationRequested",
                    payload={
                        "channel": "ops-alert",
                        "reason": "reporting_anomaly",
                        "ruleId": anomaly.rule_id,
                        "severity": anomaly.severity.value,
                        "currentValue": str(anomaly.current_value),
                        "threshold": str(anomaly.threshold),
                    },
                    correlation_id=envelope.correlationId,
                    causation_id=envelope.eventId,
                    occurred_at=anomaly.detected_at,
                )

    @staticmethod
    def _log_anomaly_alert(anomaly: AnomalyDetected) -> None:
        logger.warning(
            "Reporting anomaly detected rule_id=%s severity=%s current_value=%s threshold=%s urgent=%s",
            anomaly.rule_id,
            anomaly.severity.value,
            anomaly.current_value,
            anomaly.threshold,
            anomaly.urgent,
        )

    def _rebuild_stale_dashboards(self, envelope: EventEnvelope) -> None:
        """Rebuild dashboards synchronously for relevant consumed facts.

        Reporting has no separate scheduler in the greenfield runtime. A
        revenue-relevant upstream fact must therefore make ``dash-revenue`` leave
        BUILDING/STALE during the consumer callback and emit the
        ``ReadModelRebuilt`` fact expected by downstream observers.
        """
        now = utc_now()
        for dashboard_id, dashboard in list(self.repository.dashboards.items()):
            if dashboard.status is not ReadModelStatus.STALE:
                continue
            event_count = self._next_rebuild_event_count(dashboard, len(self.repository.consumed_events.records))
            digest = "sha256-" + hashlib.sha256(
                f"{dashboard_id}:{event_count}:{rfc3339_utc(now)}".encode()
            ).hexdigest()[:16]
            rebuild_id = prefixed_uuid7("rebuild")
            self.repository.dashboards[dashboard_id] = dashboard.rebuild(rebuild_id, now, event_count, digest)
            self.repository.rebuild_runs.setdefault(dashboard_id, []).append(
                RebuildRun(rebuild_id, dashboard_id, now, "COMPLETED", event_count, digest)
            )
            self.publish_domain_event(
                event_type="ReadModelRebuilt",
                payload={
                    "dashboardId": dashboard_id,
                    "rebuildId": rebuild_id,
                    "rebuiltAt": rfc3339_utc(now),
                    "eventCount": event_count,
                    "digest": digest,
                },
                correlation_id=envelope.correlationId,
                causation_id=envelope.eventId,
            )

    @staticmethod
    def _next_rebuild_event_count(dashboard: DashboardReadModel, consumed_count: int) -> int:
        previous_event_count = 0 if dashboard.current_snapshot is None else dashboard.current_snapshot.event_count
        return max(consumed_count, previous_event_count + 1)

    def publish_domain_event(
        self,
        *,
        event_type: str,
        payload: Mapping[str, Any],
        correlation_id: str | None,
        causation_id: str | None,
        occurred_at: datetime | None = None,
    ) -> EventEnvelope:
        envelope = EventEnvelope(
            eventId=prefixed_uuid7("evt"),
            eventType=event_type,
            occurredAt=rfc3339_utc(occurred_at or utc_now()),
            correlationId=correlation_id if correlation_id and correlation_id.startswith("corr-") else prefixed_uuid7("corr"),
            producer=PRODUCER,
            schemaVersion=1,
            payload=dict(payload),
            causationId=causation_id if causation_id and (causation_id.startswith("cmd-") or causation_id.startswith("evt-")) else None,
        )
        if self.publisher is not None:
            self.publisher.publish(envelope)
        return envelope
