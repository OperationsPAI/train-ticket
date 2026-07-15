from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from enum import Enum
from statistics import median
from typing import Any, Mapping, Self


class ReportingError(ValueError):
    """Raised when reporting domain invariants are violated."""


class MetricStatus(str, Enum):
    DRAFT = "draft"
    IN_REVIEW = "in_review"
    APPROVED = "approved"
    PUBLISHED = "published"
    SUPERSEDED = "superseded"
    DEPRECATED = "deprecated"
    REJECTED = "rejected"


class MetricGranularity(str, Enum):
    DAILY = "daily"
    WEEKLY = "weekly"
    MONTHLY = "monthly"
    QUARTERLY = "quarterly"
    YEARLY = "yearly"
    CUMULATIVE = "cumulative"


class MetricCategory(str, Enum):
    OPERATIONAL = "operational"
    FINANCIAL = "financial"
    QUALITY = "quality"
    CUSTOMER_SERVICE = "customer_service"
    SUPPLIER = "supplier"
    NOTIFICATION = "notification"


class ReadModelStatus(str, Enum):
    BUILDING = "building"
    READY = "ready"
    STALE = "stale"
    FAILED = "failed"


class FunnelStep(str, Enum):
    SEARCH = "search"
    OFFER_QUOTED = "offer_quoted"
    ORDER_CREATED = "order_created"
    BOOKING_CONFIRMED = "booking_confirmed"
    PAYMENT_CAPTURED = "payment_captured"
    ENTITLEMENT_ISSUED = "entitlement_issued"
    FULFILLMENT_COMPLETED = "fulfillment_completed"


class ConsumptionStatus(str, Enum):
    PENDING = "pending"
    PROCESSED = "processed"
    FAILED = "failed"


# ---------------------------------------------------------------------------
# Cross-context value objects (conformant to shared-primitives.md)
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Money:
    amount: Decimal
    currency: str

    def __init__(self, amount: Decimal | str | int, currency: str) -> None:
        normalized_currency = currency.strip().upper()
        if len(normalized_currency) != 3 or not normalized_currency.isalpha():
            raise ReportingError("currency must be a three-letter ISO code")
        from decimal import ROUND_HALF_UP

        quantized = Decimal(str(amount)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
        object.__setattr__(self, "amount", quantized)
        object.__setattr__(self, "currency", normalized_currency)

    def __add__(self, other: Self) -> Self:
        if self.currency != other.currency:
            raise ReportingError(f"currency mismatch: {self.currency} != {other.currency}")
        return Money(self.amount + other.amount, self.currency)

    def __sub__(self, other: Self) -> Self:
        if self.currency != other.currency:
            raise ReportingError(f"currency mismatch: {self.currency} != {other.currency}")
        return Money(self.amount - other.amount, self.currency)

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, Money):
            return NotImplemented
        return self.amount == other.amount and self.currency == other.currency

    @classmethod
    def zero(cls, currency: str) -> Self:
        return cls(Decimal("0.00"), currency)


@dataclass(frozen=True, slots=True)
class EventMetadata:
    """Standard cross-context event envelope per shared-primitives.md."""

    event_id: str
    occurred_at: datetime
    source_command_id: str
    causation_id: str
    correlation_id: str
    schema_version: int = 1
    attributes: Mapping[str, str] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.event_id.strip():
            raise ReportingError("event_id is required")
        if not self.source_command_id.strip():
            raise ReportingError("source_command_id is required")


@dataclass(frozen=True, slots=True)
class ConsumedEventRecord:
    """Idempotent event consumption tracking, conforming to shared-primitives.md."""

    event_id: str
    consumed_at: datetime
    source: str
    event_type: str
    status: ConsumptionStatus = ConsumptionStatus.PROCESSED

    def __post_init__(self) -> None:
        if not self.event_id.strip():
            raise ReportingError("consumed event id is required")
        if not self.source.strip():
            raise ReportingError("consumed event source is required")


# ---------------------------------------------------------------------------
# MetricDefinition aggregate
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class MetricDefinition:
    """A versioned metric definition. Read-only metric metadata aggregate.

    MetricDefinitions define how a business metric is calculated. They are
    versioned so dashboards and reports can reference a specific version.
    """

    metric_id: str
    name: str
    description: str
    owner: str
    category: MetricCategory
    granularity: MetricGranularity
    version: str
    expression: str
    status: MetricStatus = MetricStatus.DRAFT
    published_at: datetime | None = None
    depends_on_metric_ids: tuple[str, ...] = field(default_factory=tuple)
    source_lineage: tuple[str, ...] = field(default_factory=tuple)
    deprecated_at: datetime | None = None

    def __post_init__(self) -> None:
        required = {
            "metric_id": self.metric_id,
            "name": self.name,
            "owner": self.owner,
            "version": self.version,
            "expression": self.expression,
        }
        for field_name, value in required.items():
            if not value.strip():
                raise ReportingError(f"{field_name} is required")
        if self.status is MetricStatus.PUBLISHED and self.published_at is None:
            raise ReportingError("published metrics must record published_at")

    def publish(self, at: datetime | None = None) -> Self:
        if self.status not in {MetricStatus.APPROVED, MetricStatus.DRAFT, MetricStatus.IN_REVIEW}:
            raise ReportingError(
                f"cannot publish metric in status {self.status.value}"
            )
        return MetricDefinition(
            self.metric_id,
            self.name,
            self.description,
            self.owner,
            self.category,
            self.granularity,
            self.version,
            self.expression,
            MetricStatus.PUBLISHED,
            at or datetime.now(UTC),
            self.depends_on_metric_ids,
            self.source_lineage,
        )

    def supersede(self, at: datetime | None = None) -> Self:
        if self.status is not MetricStatus.PUBLISHED:
            raise ReportingError("only published metrics can be superseded")
        return MetricDefinition(
            self.metric_id,
            self.name,
            self.description,
            self.owner,
            self.category,
            self.granularity,
            self.version,
            self.expression,
            MetricStatus.SUPERSEDED,
            self.published_at,
            self.depends_on_metric_ids,
            self.source_lineage,
        )

    def deprecate(self, at: datetime | None = None) -> Self:
        if self.status not in {MetricStatus.PUBLISHED, MetricStatus.SUPERSEDED}:
            raise ReportingError("only published or superseded metrics can be deprecated")
        return MetricDefinition(
            self.metric_id,
            self.name,
            self.description,
            self.owner,
            self.category,
            self.granularity,
            self.version,
            self.expression,
            MetricStatus.DEPRECATED,
            self.published_at,
            self.depends_on_metric_ids,
            self.source_lineage,
            at or datetime.now(UTC),
        )


# ---------------------------------------------------------------------------
# DashboardReadModel aggregate
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class MetricRef:
    """Reference from a dashboard to a specific metric version."""

    metric_id: str
    version: str

    def __post_init__(self) -> None:
        if not self.metric_id.strip():
            raise ReportingError("metric_ref metric_id is required")
        if not self.version.strip():
            raise ReportingError("metric_ref version is required")


@dataclass(frozen=True, slots=True)
class ReadModelSnapshot:
    """Immutable snapshot of a read model at a given rebuild."""

    rebuild_id: str
    rebuilt_at: datetime
    metrics: tuple[MetricRef, ...]
    event_count: int
    digest: str

    def __post_init__(self) -> None:
        if not self.rebuild_id.strip():
            raise ReportingError("rebuild_id is required")


@dataclass(frozen=True, slots=True)
class DashboardReadModel:
    """A rebuildable dashboard read model projection.

    Read models are built from business events and Finance views. They are
    strictly read-only projections. A rebuild never blocks event consumption.
    """

    dashboard_id: str
    name: str
    description: str
    status: ReadModelStatus = ReadModelStatus.BUILDING
    metrics: tuple[MetricRef, ...] = field(default_factory=tuple)
    current_snapshot: ReadModelSnapshot | None = None
    last_built_at: datetime | None = None
    source_events: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        if not self.dashboard_id.strip():
            raise ReportingError("dashboard_id is required")
        if not self.name.strip():
            raise ReportingError("dashboard name is required")

    def add_metric(self, metric_ref: MetricRef) -> Self:
        if any(m.metric_id == metric_ref.metric_id for m in self.metrics):
            raise ReportingError(
                f"metric {metric_ref.metric_id} is already referenced in dashboard"
            )
        return DashboardReadModel(
            self.dashboard_id,
            self.name,
            self.description,
            self.status,
            self.metrics + (metric_ref,),
            self.current_snapshot,
            self.last_built_at,
            self.source_events,
        )

    def rebuild(
        self,
        rebuild_id: str,
        rebuilt_at: datetime,
        event_count: int,
        digest: str,
    ) -> Self:
        if not self.metrics:
            raise ReportingError("cannot rebuild a dashboard with no metrics")
        snapshot = ReadModelSnapshot(
            rebuild_id,
            rebuilt_at,
            self.metrics,
            event_count,
            digest,
        )
        return DashboardReadModel(
            self.dashboard_id,
            self.name,
            self.description,
            ReadModelStatus.READY,
            self.metrics,
            snapshot,
            rebuilt_at,
            self.source_events,
        )

    def mark_stale(self) -> Self:
        if self.status is ReadModelStatus.FAILED:
            raise ReportingError("cannot mark a failed dashboard as stale")
        return DashboardReadModel(
            self.dashboard_id,
            self.name,
            self.description,
            ReadModelStatus.STALE,
            self.metrics,
            self.current_snapshot,
            self.last_built_at,
            self.source_events,
        )


# ---------------------------------------------------------------------------
# FunnelView aggregate
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class FunnelStepCount:
    step: FunnelStep
    count: int
    label: str = ""

    def __post_init__(self) -> None:
        if self.count < 0:
            raise ReportingError("funnel step count cannot be negative")


@dataclass(frozen=True, slots=True)
class FunnelView:
    """A funnel view read model built from business events.

    Funnel views track conversion between pipeline stages (search -> order ->
    payment -> entitlement -> fulfillment). They are strictly read-only.
    """

    funnel_id: str
    name: str
    dimension_filters: tuple[str, ...] = field(default_factory=tuple)
    steps: tuple[FunnelStepCount, ...] = field(default_factory=tuple)
    last_built_at: datetime | None = None
    event_window_start: datetime | None = None
    event_window_end: datetime | None = None

    def __post_init__(self) -> None:
        if not self.funnel_id.strip():
            raise ReportingError("funnel_id is required")
        if not self.name.strip():
            raise ReportingError("funnel name is required")
        seen = set()
        for step in self.steps:
            if step.step in seen:
                raise ReportingError(f"duplicate funnel step: {step.step.value}")
            seen.add(step.step)

    def update_step(self, step: FunnelStep, count: int, label: str = "") -> Self:
        updated = tuple(
            FunnelStepCount(s.step, s.count, s.label) if s.step != step else FunnelStepCount(step, count, label or s.label)
            for s in self.steps
        )
        return FunnelView(
            self.funnel_id,
            self.name,
            self.dimension_filters,
            updated,
            self.last_built_at,
            self.event_window_start,
            self.event_window_end,
        )

    def rebuild(self, at: datetime, steps: tuple[FunnelStepCount, ...]) -> Self:
        return FunnelView(
            self.funnel_id,
            self.name,
            self.dimension_filters,
            steps,
            at,
            self.event_window_start,
            self.event_window_end,
        )


# ---------------------------------------------------------------------------
# ConsumedEventLog aggregate (idempotent event consumption tracking)
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class ConsumedEventLog:
    """Idempotent event consumption tracking for Reporting.

    All consumed upstream events must be recorded here to guarantee
    idempotent processing.
    """

    log_id: str
    records: tuple[ConsumedEventRecord, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        if not self.log_id.strip():
            raise ReportingError("consumed event log id is required")

    def record(self, record: ConsumedEventRecord) -> Self:
        if any(r.event_id == record.event_id for r in self.records):
            # Idempotent: skip duplicate recording
            return self
        return ConsumedEventLog(
            self.log_id,
            self.records + (record,),
        )

    def has_consumed(self, event_id: str) -> bool:
        return any(r.event_id == event_id for r in self.records)


# ---------------------------------------------------------------------------
# Real-time metrics, revenue analytics, and anomaly detection
# ---------------------------------------------------------------------------


class AnomalySeverity(str, Enum):
    INFO = "info"
    WARNING = "warning"
    CRITICAL = "critical"


class AnomalyRuleId(str, Enum):
    REVENUE_DROP = "REVENUE_DROP"
    ORDER_SPIKE = "ORDER_SPIKE"
    ERROR_RATE_SPIKE = "ERROR_RATE_SPIKE"
    CAPACITY_EXHAUSTION = "CAPACITY_EXHAUSTION"
    REFUND_SURGE = "REFUND_SURGE"


_ORDER_EVENT_TYPES = {
    "JourneyOrderCreated",
    "JourneyOrderConfirmed",
    "OrderCreated",
    "OrderConfirmed",
    "BookingConfirmed",
}
_SEARCH_EVENT_TYPES = {"TripSearched", "SearchPerformed", "OfferSearchRequested"}
_PAYMENT_CAPTURED_EVENT_TYPES = {"PaymentCaptured", "RevenueRecognized", "PaymentSucceeded"}
_PAYMENT_FAILED_EVENT_TYPES = {"PaymentFailed", "PaymentDeclined", "PaymentCaptureFailed"}
_REFUND_EVENT_TYPES = {"RefundSettled", "RefundCompleted", "RefundIssued"}
_CAPACITY_EVENT_TYPES = {"CapacityUpdated", "SeatInventoryUpdated", "CapacityExhausted"}
_RISK_BLOCK_EVENT_TYPES = {"ScalperBlocked", "RiskBookingBlocked"}


@dataclass(frozen=True, slots=True)
class OperationalEvent:
    """Normalized upstream event used by Reporting projections.

    Reporting consumes events from every context. The application layer maps the
    heterogeneous envelopes into this domain shape so aggregation rules stay in
    the domain layer.
    """

    event_id: str
    event_type: str
    occurred_at: datetime
    route_id: str | None = None
    service_date: date | None = None
    seat_class: str | None = None
    amount: Money | None = None
    channel: str | None = None
    passenger_type: str | None = None
    capacity: int | None = None
    confirmed: int | None = None
    booking_latency_ms: int | None = None
    payment_failed: bool = False
    refunded: bool = False
    scalper_blocked: bool = False
    distance_km: Decimal | None = None
    ancillary_attached: bool = False
    insurance_attached: bool = False
    source_context: str | None = None
    anomaly_signal: bool = False

    def __post_init__(self) -> None:
        if not self.event_id.strip():
            raise ReportingError("operational event_id is required")
        if not self.event_type.strip():
            raise ReportingError("operational event_type is required")
        if self.capacity is not None and self.capacity < 0:
            raise ReportingError("capacity cannot be negative")
        if self.confirmed is not None and self.confirmed < 0:
            raise ReportingError("confirmed count cannot be negative")
        if self.booking_latency_ms is not None and self.booking_latency_ms < 0:
            raise ReportingError("booking latency cannot be negative")
        if self.distance_km is not None and self.distance_km < 0:
            raise ReportingError("distance_km cannot be negative")

    @property
    def is_order(self) -> bool:
        return self.event_type in _ORDER_EVENT_TYPES

    @property
    def is_search(self) -> bool:
        return self.event_type in _SEARCH_EVENT_TYPES

    @property
    def is_payment_captured(self) -> bool:
        return self.event_type in _PAYMENT_CAPTURED_EVENT_TYPES and self.amount is not None

    @property
    def is_payment_failed(self) -> bool:
        return self.payment_failed or self.event_type in _PAYMENT_FAILED_EVENT_TYPES

    @property
    def is_refund(self) -> bool:
        return self.refunded or self.event_type in _REFUND_EVENT_TYPES

    @property
    def is_capacity_update(self) -> bool:
        return self.event_type in _CAPACITY_EVENT_TYPES or self.capacity is not None or self.confirmed is not None

    @property
    def is_scalper_block(self) -> bool:
        return self.scalper_blocked or self.event_type in _RISK_BLOCK_EVENT_TYPES


@dataclass(frozen=True, slots=True)
class RouteMetrics:
    route_id: str
    service_date: date
    route_revenue: Money
    route_demand: int
    bookings: int
    searches: int
    confirmed: int
    capacity: int
    seat_utilization_by_class: Mapping[str, float] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.route_id.strip():
            raise ReportingError("route_id is required")
        if self.route_demand < 0 or self.bookings < 0 or self.searches < 0:
            raise ReportingError("route demand counters cannot be negative")
        if self.confirmed < 0 or self.capacity < 0:
            raise ReportingError("route capacity counters cannot be negative")
        for seat_class, utilization in self.seat_utilization_by_class.items():
            if not seat_class.strip():
                raise ReportingError("seat class is required")
            if utilization < 0:
                raise ReportingError("seat utilization cannot be negative")

    @property
    def fill_rate(self) -> float:
        if self.capacity == 0:
            return 0.0
        return min(1.0, self.confirmed / self.capacity)


@dataclass(frozen=True, slots=True)
class ContextEventRollup:
    source_context: str
    event_type: str
    count: int
    last_occurred_at: datetime
    anomaly_count: int = 0

    def __post_init__(self) -> None:
        if not self.source_context.strip():
            raise ReportingError("context rollup source_context is required")
        if not self.event_type.strip():
            raise ReportingError("context rollup event_type is required")
        if self.count < 1:
            raise ReportingError("context rollup count must be positive")
        if self.anomaly_count < 0 or self.anomaly_count > self.count:
            raise ReportingError("context rollup anomaly_count must be between zero and count")

    @property
    def anomaly_rate(self) -> float:
        return self.anomaly_count / self.count


@dataclass(frozen=True, slots=True)
class MetricSnapshot:
    generated_at: datetime
    orders_per_second: int
    revenue_per_hour: Money
    fill_rate_by_route: Mapping[str, float]
    avg_booking_latency: Mapping[str, float]
    refund_rate: float
    scalper_block_rate: float
    route_metrics: tuple[RouteMetrics, ...] = ()
    context_rollups: tuple[ContextEventRollup, ...] = ()


@dataclass(frozen=True, slots=True)
class ContextCountItem:
    dimension: str
    value: str
    count: int

    def __post_init__(self) -> None:
        if self.dimension not in {"source_context", "event_type"}:
            raise ReportingError("context count item dimension is invalid")
        if not self.value.strip():
            raise ReportingError("context count item value is required")
        if self.count < 1:
            raise ReportingError("context count item count must be positive")


@dataclass(frozen=True, slots=True)
class ContextCountReport:
    generated_at: datetime
    group_by: str
    items: tuple[ContextCountItem, ...]
    total_count: int

    def __post_init__(self) -> None:
        if self.group_by not in {"source_context", "event_type"}:
            raise ReportingError("context count report dimension is invalid")
        if self.total_count < 0:
            raise ReportingError("context count report total_count cannot be negative")


@dataclass(frozen=True, slots=True)
class RevenueItem:
    dimension: str
    value: str
    revenue: Money
    count: int
    yield_per_km: Decimal = Decimal("0.00")
    ancillary_attach_rate: float = 0.0
    insurance_attach_rate: float = 0.0

    def __post_init__(self) -> None:
        if not self.dimension.strip():
            raise ReportingError("revenue item dimension is required")
        if not self.value.strip():
            raise ReportingError("revenue item value is required")
        if self.count < 0:
            raise ReportingError("revenue item count cannot be negative")
        if self.yield_per_km < 0:
            raise ReportingError("yield_per_km cannot be negative")
        if not 0 <= self.ancillary_attach_rate <= 1:
            raise ReportingError("ancillary_attach_rate must be between 0 and 1")
        if not 0 <= self.insurance_attach_rate <= 1:
            raise ReportingError("insurance_attach_rate must be between 0 and 1")


@dataclass(frozen=True, slots=True)
class RevenueReport:
    generated_at: datetime
    group_by: str
    items: tuple[RevenueItem, ...]
    total_revenue: Money


@dataclass(frozen=True, slots=True)
class AnomalyRule:
    rule_id: AnomalyRuleId
    threshold: Decimal
    severity: AnomalySeverity
    description: str
    urgent: bool = False


@dataclass(frozen=True, slots=True)
class AnomalyDetected:
    rule_id: str
    current_value: Decimal
    threshold: Decimal
    severity: AnomalySeverity
    detected_at: datetime
    urgent: bool = False
    metadata: EventMetadata | None = None

    def __post_init__(self) -> None:
        if not self.rule_id.strip():
            raise ReportingError("anomaly rule_id is required")


@dataclass(slots=True)
class MetricAggregator:
    """Production-realistic in-memory projection for real-time dashboards."""

    currency: str = "USD"
    events: list[OperationalEvent] = field(default_factory=list)

    def record(self, event: OperationalEvent) -> None:
        if event.amount is not None and event.amount.currency != self.currency:
            raise ReportingError(f"metric aggregator currency mismatch: {event.amount.currency} != {self.currency}")
        if any(existing.event_id == event.event_id for existing in self.events):
            return
        self.events.append(event)

    def snapshot(self, at: datetime | None = None) -> MetricSnapshot:
        now = at or datetime.now(UTC)
        orders_60s = [event for event in self._since(now, timedelta(seconds=60)) if event.is_order]
        revenue_1h = sum((event.amount.amount for event in self._since(now, timedelta(hours=1)) if event.is_payment_captured and event.amount is not None), Decimal("0.00"))
        latencies = [event.booking_latency_ms for event in self.events if event.booking_latency_ms is not None]
        refund_events = [event for event in self._since(now, timedelta(hours=24)) if event.is_refund]
        order_events_24h = [event for event in self._since(now, timedelta(hours=24)) if event.is_order]
        risk_events_1h = [event for event in self._since(now, timedelta(hours=1)) if event.is_scalper_block or event.is_order]
        blocked_1h = [event for event in risk_events_1h if event.is_scalper_block]
        routes = self.route_metrics(at=now)
        return MetricSnapshot(
            generated_at=now,
            orders_per_second=len(orders_60s),
            revenue_per_hour=Money(revenue_1h, self.currency),
            fill_rate_by_route={route.route_id: route.fill_rate for route in routes},
            avg_booking_latency=self._latency_percentiles(latencies),
            refund_rate=self._rate(len(refund_events), len(order_events_24h)),
            scalper_block_rate=self._rate(len(blocked_1h), len(risk_events_1h)),
            route_metrics=routes,
            context_rollups=self.context_rollups(),
        )

    def route_metrics(self, at: datetime | None = None) -> tuple[RouteMetrics, ...]:
        now = at or datetime.now(UTC)
        grouped: dict[tuple[str, date], list[OperationalEvent]] = {}
        for event in self.events:
            if event.route_id is None:
                continue
            service_date = event.service_date or event.occurred_at.astimezone(UTC).date()
            grouped.setdefault((event.route_id, service_date), []).append(event)
        metrics: list[RouteMetrics] = []
        for (route_id, service_date), events in grouped.items():
            revenue = sum((event.amount.amount for event in events if event.is_payment_captured and event.amount is not None), Decimal("0.00"))
            searches = sum(1 for event in events if event.is_search)
            bookings = sum(1 for event in events if event.is_order)
            confirmed = self._latest_int(events, "confirmed") or bookings
            capacity = self._latest_int(events, "capacity") or 0
            by_class = self._seat_utilization(events)
            metrics.append(
                RouteMetrics(
                    route_id=route_id,
                    service_date=service_date,
                    route_revenue=Money(revenue, self.currency),
                    route_demand=searches + bookings,
                    bookings=bookings,
                    searches=searches,
                    confirmed=confirmed,
                    capacity=capacity,
                    seat_utilization_by_class=by_class,
                )
            )
        metrics.sort(key=lambda item: (item.service_date, item.route_id))
        return tuple(metrics)

    def context_rollups(self) -> tuple[ContextEventRollup, ...]:
        grouped: dict[tuple[str, str], dict[str, Any]] = {}
        for event in self.events:
            context = event.source_context or "unknown"
            bucket = grouped.setdefault(
                (context, event.event_type),
                {"count": 0, "last_occurred_at": event.occurred_at, "anomaly_count": 0},
            )
            bucket["count"] += 1
            if event.occurred_at > bucket["last_occurred_at"]:
                bucket["last_occurred_at"] = event.occurred_at
            if event.anomaly_signal:
                bucket["anomaly_count"] += 1
        return tuple(
            ContextEventRollup(context, event_type, bucket["count"], bucket["last_occurred_at"], bucket["anomaly_count"])
            for (context, event_type), bucket in sorted(grouped.items())
        )

    def context_count_report(self, group_by: str = "source_context", limit: int = 20, at: datetime | None = None) -> ContextCountReport:
        if limit < 1:
            raise ReportingError("context count report limit must be positive")
        dimensions = {
            "source_context": lambda event: event.source_context,
            "event_type": lambda event: event.event_type,
        }
        if group_by not in dimensions:
            raise ReportingError("unsupported context count report dimension")
        grouped: dict[str, int] = {}
        for event in self.events:
            value = dimensions[group_by](event) or "unknown"
            grouped[value] = grouped.get(value, 0) + 1
        items = tuple(
            ContextCountItem(group_by, value, count)
            for value, count in sorted(grouped.items(), key=lambda item: (-item[1], item[0]))[:limit]
        )
        return ContextCountReport(at or datetime.now(UTC), group_by, items, sum(grouped.values()))

    def revenue_report(self, group_by: str = "route", limit: int = 20, at: datetime | None = None) -> RevenueReport:
        if limit < 1:
            raise ReportingError("revenue report limit must be positive")
        now = at or datetime.now(UTC)
        dimensions = {
            "route": lambda event: event.route_id,
            "seat_class": lambda event: event.seat_class,
            "channel": lambda event: event.channel,
            "passenger_type": lambda event: event.passenger_type,
            "time_period": lambda event: event.occurred_at.astimezone(UTC).strftime("%Y-%m-%dT%H:00:00Z"),
        }
        if group_by not in dimensions:
            raise ReportingError("unsupported revenue report dimension")
        grouped: dict[str, list[OperationalEvent]] = {}
        for event in self.events:
            if not event.is_payment_captured or event.amount is None:
                continue
            value = dimensions[group_by](event) or "unknown"
            grouped.setdefault(value, []).append(event)
        items = tuple(
            sorted(
                (self._revenue_item(group_by, value, events) for value, events in grouped.items()),
                key=lambda item: (-item.revenue.amount, item.value),
            )[:limit]
        )
        total = sum((item.revenue.amount for item in items), Decimal("0.00"))
        return RevenueReport(now, group_by, items, Money(total, self.currency))

    def _since(self, now: datetime, window: timedelta) -> list[OperationalEvent]:
        start = now - window
        return [event for event in self.events if start <= event.occurred_at <= now]

    @staticmethod
    def _rate(numerator: int, denominator: int) -> float:
        if denominator == 0:
            return 0.0
        return numerator / denominator

    @staticmethod
    def _latency_percentiles(values: list[int]) -> dict[str, float]:
        if not values:
            return {"p50": 0.0, "p95": 0.0, "p99": 0.0}
        ordered = sorted(values)

        def percentile(percent: float) -> float:
            index = min(len(ordered) - 1, max(0, int(round((len(ordered) - 1) * percent))))
            return float(ordered[index])

        return {"p50": float(median(ordered)), "p95": percentile(0.95), "p99": percentile(0.99)}

    @staticmethod
    def _latest_int(events: list[OperationalEvent], field_name: str) -> int | None:
        for event in sorted(events, key=lambda item: item.occurred_at, reverse=True):
            value = getattr(event, field_name)
            if value is not None:
                return int(value)
        return None

    @staticmethod
    def _seat_utilization(events: list[OperationalEvent]) -> dict[str, float]:
        by_class: dict[str, dict[str, int]] = {}
        for event in events:
            if event.seat_class is None:
                continue
            bucket = by_class.setdefault(event.seat_class, {"confirmed": 0, "capacity": 0})
            if event.is_order:
                bucket["confirmed"] += 1
            if event.capacity is not None:
                bucket["capacity"] = event.capacity
            if event.confirmed is not None:
                bucket["confirmed"] = event.confirmed
        return {
            seat_class: (0.0 if values["capacity"] == 0 else min(1.0, values["confirmed"] / values["capacity"]))
            for seat_class, values in by_class.items()
        }

    def _revenue_item(self, dimension: str, value: str, events: list[OperationalEvent]) -> RevenueItem:
        revenue = sum((event.amount.amount for event in events if event.amount is not None), Decimal("0.00"))
        count = sum(1 for event in events if event.amount is not None)
        distance = sum((event.distance_km or Decimal("0")) for event in events)
        yield_per_km = Decimal("0.00") if distance == 0 else (revenue / distance).quantize(Decimal("0.01"))
        return RevenueItem(
            dimension=dimension,
            value=value,
            revenue=Money(revenue, self.currency),
            count=count,
            yield_per_km=yield_per_km,
            ancillary_attach_rate=self._rate(sum(1 for event in events if event.ancillary_attached), len(events)),
            insurance_attach_rate=self._rate(sum(1 for event in events if event.insurance_attached), len(events)),
        )


@dataclass(slots=True)
class AnomalyDetector:
    rules: tuple[AnomalyRule, ...] = (
        AnomalyRule(AnomalyRuleId.REVENUE_DROP, Decimal("0.50"), AnomalySeverity.CRITICAL, "Revenue is below 50% of same hour yesterday", True),
        AnomalyRule(AnomalyRuleId.ORDER_SPIKE, Decimal("3.00"), AnomalySeverity.WARNING, "Orders exceed 3x rolling average"),
        AnomalyRule(AnomalyRuleId.ERROR_RATE_SPIKE, Decimal("0.10"), AnomalySeverity.CRITICAL, "Payment failures exceed 10% in five minutes", True),
        AnomalyRule(AnomalyRuleId.CAPACITY_EXHAUSTION, Decimal("5"), AnomalySeverity.WARNING, "More than five routes are at 100% capacity"),
        AnomalyRule(AnomalyRuleId.REFUND_SURGE, Decimal("0.20"), AnomalySeverity.WARNING, "Refunds exceed 20% in one hour"),
    )
    active: dict[str, AnomalyDetected] = field(default_factory=dict)

    def evaluate(self, aggregator: MetricAggregator, at: datetime | None = None) -> tuple[AnomalyDetected, ...]:
        now = at or datetime.now(UTC)
        detected: list[AnomalyDetected] = []
        checks = (
            self._revenue_drop(aggregator, now),
            self._order_spike(aggregator, now),
            self._error_rate_spike(aggregator, now),
            self._capacity_exhaustion(aggregator, now),
            self._refund_surge(aggregator, now),
        )
        triggered_rule_ids: set[str] = set()
        for anomaly in checks:
            if anomaly is None:
                continue
            triggered_rule_ids.add(anomaly.rule_id)
            previous = self.active.get(anomaly.rule_id)
            self.active[anomaly.rule_id] = anomaly
            if previous is None or previous.current_value != anomaly.current_value:
                detected.append(anomaly)
        for rule_id in set(self.active) - triggered_rule_ids:
            del self.active[rule_id]
        return tuple(detected)

    def list_active(self) -> tuple[AnomalyDetected, ...]:
        return tuple(sorted(self.active.values(), key=lambda item: item.detected_at, reverse=True))

    def _rule(self, rule_id: AnomalyRuleId) -> AnomalyRule:
        return next(rule for rule in self.rules if rule.rule_id is rule_id)

    def _anomaly(self, rule_id: AnomalyRuleId, current: Decimal, now: datetime) -> AnomalyDetected:
        rule = self._rule(rule_id)
        return AnomalyDetected(rule.rule_id.value, current, rule.threshold, rule.severity, now, rule.urgent)

    def _revenue_drop(self, aggregator: MetricAggregator, now: datetime) -> AnomalyDetected | None:
        current = sum((event.amount.amount for event in aggregator._since(now, timedelta(hours=1)) if event.is_payment_captured and event.amount), Decimal("0.00"))
        yesterday_end = now - timedelta(days=1)
        yesterday_start = yesterday_end - timedelta(hours=1)
        yesterday = sum((event.amount.amount for event in aggregator.events if yesterday_start <= event.occurred_at <= yesterday_end and event.is_payment_captured and event.amount), Decimal("0.00"))
        if yesterday > 0 and current < (yesterday * self._rule(AnomalyRuleId.REVENUE_DROP).threshold):
            return self._anomaly(AnomalyRuleId.REVENUE_DROP, (current / yesterday).quantize(Decimal("0.01")), now)
        return None

    def _order_spike(self, aggregator: MetricAggregator, now: datetime) -> AnomalyDetected | None:
        current = Decimal(sum(1 for event in aggregator._since(now, timedelta(minutes=1)) if event.is_order))
        prior_start = now - timedelta(minutes=6)
        prior_end = now - timedelta(minutes=1)
        prior = sum(1 for event in aggregator.events if prior_start <= event.occurred_at < prior_end and event.is_order)
        avg = Decimal(prior) / Decimal("5") if prior else Decimal("0")
        if avg > 0 and current > avg * self._rule(AnomalyRuleId.ORDER_SPIKE).threshold:
            return self._anomaly(AnomalyRuleId.ORDER_SPIKE, (current / avg).quantize(Decimal("0.01")), now)
        return None

    def _error_rate_spike(self, aggregator: MetricAggregator, now: datetime) -> AnomalyDetected | None:
        recent = [event for event in aggregator._since(now, timedelta(minutes=5)) if event.is_payment_failed or event.event_type in _PAYMENT_CAPTURED_EVENT_TYPES]
        failed = sum(1 for event in recent if event.is_payment_failed)
        rate = Decimal(str(MetricAggregator._rate(failed, len(recent))))
        if len(recent) > 0 and rate > self._rule(AnomalyRuleId.ERROR_RATE_SPIKE).threshold:
            return self._anomaly(AnomalyRuleId.ERROR_RATE_SPIKE, rate.quantize(Decimal("0.01")), now)
        return None

    def _capacity_exhaustion(self, aggregator: MetricAggregator, now: datetime) -> AnomalyDetected | None:
        exhausted = sum(1 for route in aggregator.route_metrics(now) if route.capacity > 0 and route.confirmed >= route.capacity)
        threshold = self._rule(AnomalyRuleId.CAPACITY_EXHAUSTION).threshold
        if Decimal(exhausted) > threshold:
            return self._anomaly(AnomalyRuleId.CAPACITY_EXHAUSTION, Decimal(exhausted), now)
        return None

    def _refund_surge(self, aggregator: MetricAggregator, now: datetime) -> AnomalyDetected | None:
        recent = aggregator._since(now, timedelta(hours=1))
        refunds = sum(1 for event in recent if event.is_refund)
        orders = sum(1 for event in recent if event.is_order)
        rate = Decimal(str(MetricAggregator._rate(refunds, orders)))
        if orders > 0 and rate > self._rule(AnomalyRuleId.REFUND_SURGE).threshold:
            return self._anomaly(AnomalyRuleId.REFUND_SURGE, rate.quantize(Decimal("0.01")), now)
        return None

# ---------------------------------------------------------------------------
# Domain commands
# ---------------------------------------------------------------------------


def define_metric(
    *,
    metric_id: str,
    name: str,
    description: str,
    owner: str,
    category: MetricCategory,
    granularity: MetricGranularity,
    version: str,
    expression: str,
    depends_on_metric_ids: tuple[str, ...] = (),
    source_lineage: tuple[str, ...] = (),
) -> MetricDefinition:
    return MetricDefinition(
        metric_id=metric_id,
        name=name,
        description=description,
        owner=owner,
        category=category,
        granularity=granularity,
        version=version,
        expression=expression,
        status=MetricStatus.DRAFT,
        depends_on_metric_ids=depends_on_metric_ids,
        source_lineage=source_lineage,
    )


def publish_metric_version(
    metric: MetricDefinition,
    at: datetime | None = None,
) -> MetricDefinition:
    return metric.publish(at)


def project_event(
    dashboard: DashboardReadModel,
    *,
    rebuild_id: str,
    rebuilt_at: datetime,
    event_count: int,
    digest: str,
) -> DashboardReadModel:
    return dashboard.rebuild(rebuild_id, rebuilt_at, event_count, digest)


def rebuild_read_model(
    funnel: FunnelView,
    at: datetime,
    steps: tuple[FunnelStepCount, ...],
) -> FunnelView:
    return funnel.rebuild(at, steps)


# ---------------------------------------------------------------------------
# Domain events
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class MetricDefined:
    metric: MetricDefinition
    metadata: EventMetadata


@dataclass(frozen=True, slots=True)
class MetricVersionPublished:
    metric: MetricDefinition
    previous_status: MetricStatus
    metadata: EventMetadata


@dataclass(frozen=True, slots=True)
class ReadModelRebuilt:
    dashboard_id: str
    snapshot: ReadModelSnapshot
    metadata: EventMetadata
