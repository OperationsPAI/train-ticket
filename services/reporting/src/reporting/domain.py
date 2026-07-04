from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from decimal import Decimal
from enum import Enum
from typing import Mapping, Self


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
