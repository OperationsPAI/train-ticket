from __future__ import annotations

import hashlib
import logging
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
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
    RevenueFactIndex,
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
    "JourneyOrderCreated",
    "PostSalesCaseOpened",
    "PostSalesRequested",
    "SegmentReservationRequested",
    "SeatAllocated",
    "StandingAssigned",
    "AncillaryOrderItemSelected",
    "AncillaryOrderItemConfirmed",
    "AncillaryOrderItemFulfilled",
    "AncillaryOrderItemCancelled",
    "AncillaryOrderItemRefunded",
    "InsurancePolicyIssued",
    "InsurancePolicyCancelled",
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
        "AncillaryOrderItemSelected",
        "AncillaryOrderItemConfirmed",
        "AncillaryOrderItemFulfilled",
        "AncillaryOrderItemCancelled",
        "AncillaryOrderItemRefunded",
        "InsurancePolicyIssued",
        "InsurancePolicyCancelled",
        "PostSalesCaseOpened",
        "PostSalesRequested",
        "SegmentReservationRequested",
        "SeatAllocated",
        "StandingAssigned",
        "ChannelOrderFailed",
        "ChannelRefundFailed",
        "ChannelRefundSucceeded",
    )
)

TREND_BUCKETS = 60
TREND_BUCKET_SECONDS = 60


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


def trends_from_events(events: Any, at: datetime | None = None) -> dict[str, object]:
    generated_at = at or utc_now()
    end_at = generated_at.astimezone(UTC).replace(second=0, microsecond=0)
    start_at = end_at - timedelta(minutes=TREND_BUCKETS)
    buckets = [start_at + timedelta(minutes=index) for index in range(TREND_BUCKETS)]
    orders = {bucket: 0 for bucket in buckets}
    refunds = {bucket: 0 for bucket in buckets}
    revenue: dict[datetime, dict[str, Decimal]] = {bucket: {} for bucket in buckets}
    for event in events:
        if not start_at <= event.occurred_at < end_at:
            continue
        bucket = event.occurred_at.astimezone(UTC).replace(second=0, microsecond=0)
        if event.is_order:
            orders[bucket] += 1
        if event.is_refund:
            refunds[bucket] += 1
        if event.is_payment_captured and event.amount is not None:
            values = revenue[bucket]
            values[event.amount.currency] = values.get(event.amount.currency, Decimal("0.00")) + event.amount.amount
    return {
        "generatedAt": rfc3339_utc(generated_at),
        "window": {
            "start": rfc3339_utc(start_at),
            "end": rfc3339_utc(end_at),
            "bucketSeconds": TREND_BUCKET_SECONDS,
            "pointCount": TREND_BUCKETS,
        },
        "series": [
            {
                "metric": "orders_per_second",
                "unit": "orders_per_second",
                "points": [
                    {"at": rfc3339_utc(bucket), "value": round(orders[bucket] / TREND_BUCKET_SECONDS, 4)}
                    for bucket in buckets
                ],
            },
            {
                "metric": "gross_captured_amount",
                "unit": "money_per_minute",
                "points": [
                    {
                        "at": rfc3339_utc(bucket),
                        "values": [
                            {"amount": str(Money(amount, currency).amount), "currency": currency}
                            for currency, amount in sorted(revenue[bucket].items())
                        ],
                    }
                    for bucket in buckets
                ],
            },
            {
                "metric": "refund_rate",
                "unit": "ratio",
                "points": [
                    {
                        "at": rfc3339_utc(bucket),
                        "value": 0.0 if orders[bucket] == 0 else refunds[bucket] / orders[bucket],
                    }
                    for bucket in buckets
                ],
            },
        ],
    }


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
        project_revenue_facts(self.metric_aggregator.revenue_facts, envelope)
        self.metric_aggregator.record(normalized)
        for dashboard_id, dashboard in list(self.dashboards.items()):
            if dashboard_consumes_event(dashboard, envelope.eventType):
                self.dashboards[dashboard_id] = dashboard.mark_stale()
        return True

    def operational_metrics(self, at: datetime | None = None) -> MetricSnapshot:
        return self.metric_aggregator.snapshot(at)

    def route_metrics(self, *, limit: int = 20, offset: int = 0, at: datetime | None = None) -> tuple[tuple[RouteMetrics, ...], int]:
        routes = tuple(sorted(self.metric_aggregator.route_metrics(at), key=lambda item: item.route_id))
        routes = tuple(sorted(routes, key=lambda item: item.service_date, reverse=True))
        return routes[offset : offset + limit], len(routes)

    def context_count_report(self, group_by: str = "source_context", limit: int = 20, offset: int = 0, at: datetime | None = None) -> ContextCountReport:
        return self.metric_aggregator.context_count_report(group_by=group_by, limit=limit, offset=offset, at=at)

    def revenue_report(self, group_by: str = "route", limit: int = 20, offset: int = 0, at: datetime | None = None) -> RevenueReport:
        return self.metric_aggregator.revenue_report(group_by=group_by, limit=limit, offset=offset, at=at)

    def detect_anomalies(self, at: datetime | None = None) -> tuple[AnomalyDetected, ...]:
        return self.anomaly_detector.evaluate(self.metric_aggregator, at)

    def list_anomalies(self) -> tuple[AnomalyDetected, ...]:
        return self.anomaly_detector.list_active()


def _payload_value(payload: Mapping[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in payload:
            return payload[key]
    return None


def _parse_occurred_at(value: Any) -> datetime:
    """Coerce an envelope's occurredAt to an aware UTC datetime.

    The annotation used to say `str` and the body assumed it. It is not always a
    string: python-kit's EventEnvelope carries occurredAt as a datetime once it
    has been deserialised, and `datetime.replace("Z", "+00:00")` then binds those
    two strings to the `year` and `month` keyword parameters and raises
    `TypeError: 'str' object cannot be interpreted as an integer`.

    `except ValueError` did not catch a TypeError, so this propagated out of
    handle_event and every consumed event on every subscribed stream returned
    TRANSIENT_ERROR. The effect was 13,511 pending messages, dash-revenue frozen
    in `building` with lastBuiltAt 2026-07-05, and reporting_revenue_by_route
    empty -- reporting had been consuming and discarding everything for months.
    """
    if isinstance(value, datetime):
        return (value if value.tzinfo else value.replace(tzinfo=UTC)).astimezone(UTC)
    if value is None:
        return utc_now()
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00")).astimezone(UTC)
    except (TypeError, ValueError):
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


def _text(value: Any) -> str:
    return "" if value is None else str(value).strip()


def _string_values(value: Any) -> tuple[str, ...]:
    if not isinstance(value, (list, tuple)):
        return ()
    return tuple(item for item in (_text(item) for item in value) if item)


def _scope_values(payload: Mapping[str, Any], key: str) -> tuple[str, ...]:
    scope = payload.get("scope")
    return _string_values(scope.get(key)) if isinstance(scope, Mapping) else ()


def project_revenue_facts(index: Any, envelope: EventEnvelope) -> bool:
    """Project authoritative business dimensions from their owning contexts."""
    payload = envelope.payload or {}
    occurred_at = _parse_occurred_at(envelope.occurredAt)
    event_type = envelope.eventType

    if event_type == "PaymentCaptured":
        index.record_capture(
            envelope.eventId,
            _text(payload.get("businessRef") or payload.get("orderId")),
            _text(payload.get("paymentIntentId") or payload.get("intentId")),
            _text(payload.get("purpose")),
            occurred_at,
        )
        return True

    if event_type == "JourneyOrderCreated":
        routes = []
        for segment in payload.get("segments", ()):
            if not isinstance(segment, Mapping):
                continue
            routes.append((_text(segment.get("segmentRef")), _parse_date(segment.get("departureTime"))))
        if not routes:
            routes = [(segment_ref, None) for segment_ref in _string_values(payload.get("segmentRefs"))]
        travelers = []
        for traveler in payload.get("travelerRefs", ()):
            if not isinstance(traveler, Mapping):
                continue
            travelers.append((_text(traveler.get("travelerId")), _text(traveler.get("travelerType"))))
        index.record_order(_text(payload.get("orderId")), routes, travelers, occurred_at)
        return True

    if event_type in {"PostSalesCaseOpened", "PostSalesRequested"}:
        index.record_business_order(
            _text(payload.get("caseId") or payload.get("postSalesCaseId")),
            _text(payload.get("journeyOrderId") or payload.get("orderId")),
            _scope_values(payload, "segmentRefs"),
            _scope_values(payload, "travelerRefs"),
            occurred_at,
        )
        return True

    if event_type in {"SegmentReservationRequested", "SeatAllocated", "StandingAssigned"}:
        seat_class = _text(payload.get("requestedClassRef") or payload.get("classRef"))
        index.record_seat_class(
            _text(payload.get("journeyOrderId") or payload.get("orderId")),
            _text(payload.get("segmentRef")),
            _text(payload.get("travelerRef")),
            seat_class,
            occurred_at,
            2 if event_type in {"SeatAllocated", "StandingAssigned"} else 1,
        )
        return True

    ancillary_events = {
        "AncillaryOrderItemSelected",
        "AncillaryOrderItemConfirmed",
        "AncillaryOrderItemFulfilled",
        "AncillaryOrderItemCancelled",
        "AncillaryOrderItemRefunded",
    }
    if event_type in ancillary_events:
        attachment_ref = _text(payload.get("ancillaryOrderItemId"))
        order_id = _text(payload.get("journeyOrderId") or payload.get("orderId"))
        segment_refs = (_text(payload.get("segmentRef")),) if _text(payload.get("segmentRef")) else ()
        traveler_refs = (_text(payload.get("travelerRef")),) if _text(payload.get("travelerRef")) else ()
        snapshot = payload.get("catalogSnapshot")
        service_type = _text(payload.get("serviceType"))
        if not service_type and isinstance(snapshot, Mapping):
            service_type = _text(snapshot.get("serviceType"))
        attachment_type = "INSURANCE" if service_type.upper() == "INSURANCE" else "ANCILLARY"
        index.record_business_order(attachment_ref, order_id, segment_refs, traveler_refs, occurred_at)
        if event_type in {"AncillaryOrderItemConfirmed", "AncillaryOrderItemFulfilled", "AncillaryOrderItemRefunded"}:
            index.record_attachment(order_id, attachment_type, occurred_at)
        return True

    if event_type in {"InsurancePolicyIssued", "InsurancePolicyCancelled"}:
        policy_id = _text(payload.get("policyId"))
        ancillary_ref = _text(payload.get("ancillaryOrderItemId"))
        order_id = _text(payload.get("journeyOrderId") or payload.get("orderId"))
        segment_refs = _string_values(payload.get("segmentRefs"))
        traveler_ref = _text(payload.get("travelerRef"))
        traveler_refs = (traveler_ref,) if traveler_ref else ()
        index.record_business_order(policy_id, order_id, segment_refs, traveler_refs, occurred_at)
        index.record_attachment(order_id, "INSURANCE", occurred_at)
        if ancillary_ref:
            index.record_business_order(ancillary_ref, order_id, segment_refs, traveler_refs, occurred_at)
        return True

    return False


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
        route_id=None if envelope.eventType == "PaymentCaptured" else _payload_value(payload, "routeId", "route_id", "route", "segmentRef", "serviceSegmentRef"),
        service_date=_parse_date(_payload_value(payload, "serviceDate", "travelDate", "departureDate", "requestedAt", "validFrom")),
        seat_class=None if envelope.eventType == "PaymentCaptured" else _payload_value(payload, "seatClass", "seat_class", "classRef", "requestedClassRef", "berthType"),
        amount=_parse_money(payload),
        channel=_payload_value(payload, "channel", "salesChannel", "channelId", "sourceChannel"),
        passenger_type=None if envelope.eventType == "PaymentCaptured" else _payload_value(payload, "passengerType", "passenger_type", "travelerType"),
        capacity=_parse_int(_payload_value(payload, "capacity", "totalCapacity", "seatsAvailable", "availableSeats", "availableUnits")),
        confirmed=_parse_int(_payload_value(payload, "confirmed", "confirmedSeats", "bookedSeats", "usedSeats", "allocatedSeats")),
        booking_latency_ms=_parse_int(_payload_value(payload, "bookingLatencyMs", "latencyMs", "elapsedMs")),
        payment_failed=_parse_bool(_payload_value(payload, "paymentFailed", "failed", "declined") or envelope.eventType in {"PaymentFailed", "PaymentDeclined", "PaymentCaptureFailed", "ChannelOrderFailed", "ChannelRefundFailed"}),
        refunded=_parse_bool(_payload_value(payload, "refunded") or envelope.eventType in {"RefundSettled", "RefundCompleted", "RefundIssued", "RevenueRecognitionReversed", "ChannelRefundSucceeded", "AncillaryOrderItemRefunded"}),
        scalper_blocked=_parse_bool(_payload_value(payload, "scalperBlocked", "blockedByRisk") or envelope.eventType in {"ScalperBlocked", "RiskBookingBlocked", "RiskBlockApplied"}),
        distance_km=_parse_decimal(_payload_value(payload, "distanceKm", "distance_km")),
        ancillary_attached=False if envelope.eventType == "PaymentCaptured" else _parse_bool(_payload_value(payload, "ancillaryAttached", "ancillary_attach") or envelope.eventType.startswith("Ancillary")),
        insurance_attached=False if envelope.eventType == "PaymentCaptured" else _parse_bool(_payload_value(payload, "insuranceAttached", "insurance_attach") or envelope.eventType.startswith("Insurance")),
        source_context=envelope.producer,
        anomaly_signal=anomaly_signal,
        business_ref=_text(_payload_value(payload, "businessRef", "orderId")) if envelope.eventType == "PaymentCaptured" else None,
        payment_intent_id=_text(_payload_value(payload, "paymentIntentId", "intentId")) if envelope.eventType == "PaymentCaptured" else None,
        payment_purpose=_text(payload.get("purpose")) if envelope.eventType == "PaymentCaptured" else None,
    )


def default_repository() -> ReportingReadRepository:
    published_at = datetime(2026, 7, 5, 10, 30, tzinfo=UTC)
    revenue_metric = MetricDefinition(
        metric_id="metric-revenue",
        name="Total Revenue",
        description="Gross captured payment amount by reporting period, using PaymentCaptured only; refunds and revenue recognition do not change this metric.",
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

    def route_metrics(self, *, limit: int = 20, offset: int = 0, at: datetime | None = None) -> tuple[tuple[RouteMetrics, ...], int]:
        return self.repository.route_metrics(limit=limit, offset=offset, at=at)

    def context_count_report(self, group_by: str = "source_context", limit: int = 20, offset: int = 0, at: datetime | None = None) -> ContextCountReport:
        return self.repository.context_count_report(group_by=group_by, limit=limit, offset=offset, at=at)

    def revenue_report(self, group_by: str = "route", limit: int = 20, offset: int = 0, at: datetime | None = None) -> RevenueReport:
        return self.repository.revenue_report(group_by=group_by, limit=limit, offset=offset, at=at)

    def list_anomalies(self) -> tuple[AnomalyDetected, ...]:
        return self.repository.list_anomalies()

    def trends(self, at: datetime | None = None) -> dict[str, object]:
        return trends_from_events(self.repository.metric_aggregator.events, at)

    def handle_event(self, envelope: EventEnvelope) -> HandlerResult:
        if not reporting_applies_event_type(envelope.eventType):
            return HandlerResult.success()
        try:
            if self.repository.record_consumed_event(envelope):
                self._publish_detected_anomalies(envelope)
                self._rebuild_stale_dashboards(envelope)
        except Exception as exc:  # pragma: no cover - defensive boundary for broker callback
            # Log with the traceback. Returning only str(exc) meant every event on
            # every subscribed stream failed with the bare text "'str' object
            # cannot be interpreted as an integer" and no indication of where --
            # 13,511 pending messages, a dashboard stuck in `building` since
            # 2026-07-05, and reporting_revenue_by_route empty, all attributable to
            # a message with no stack behind it.
            logger.exception(
                "reporting handler FAILED event=%s eventId=%s producer=%s -- returning "
                "TRANSIENT_ERROR, so this message stays pending and will be redelivered",
                envelope.eventType,
                envelope.eventId,
                getattr(envelope, "producer", "?"),
            )
            return HandlerResult.transient_error(f"{type(exc).__name__}: {exc}")
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
            # BUILDING as well as STALE. A dashboard that has never completed a
            # build starts BUILDING, and this loop only ever considered STALE, so
            # `dash-revenue` sat in BUILDING with lastBuiltAt 2026-07-05 and
            # reporting_revenue_by_route empty while reporting consumed events for
            # months. Nothing moves a dashboard from BUILDING to STALE either --
            # STALE is what a READY dashboard becomes when a new fact arrives -- so
            # the initial state was a dead end.
            if dashboard.status not in (ReadModelStatus.STALE, ReadModelStatus.BUILDING):
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
