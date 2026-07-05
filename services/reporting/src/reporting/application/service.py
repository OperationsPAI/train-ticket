from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

from reporting.domain import (
    ConsumedEventLog,
    ConsumedEventRecord,
    DashboardReadModel,
    MetricCategory,
    MetricDefinition,
    MetricGranularity,
    MetricRef,
    MetricStatus,
    ReadModelSnapshot,
)

from .ports import EventEnvelope, EventPublisher, HandlerResult

PRODUCER = "reporting"


def utc_now() -> datetime:
    return datetime.now(UTC)


def rfc3339_utc(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def prefixed_uuid(prefix: str) -> str:
    return f"{prefix}-{uuid4()}"


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
        for dashboard_id, dashboard in list(self.dashboards.items()):
            if not dashboard.source_events or envelope.eventType in dashboard.source_events:
                self.dashboards[dashboard_id] = dashboard.mark_stale()
        return True


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
        source_lineage=("PaymentCaptured", "RefundSettled"),
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
        source_events=("RevenueRecognized", "ReconciliationCompleted", "InvoiceGenerated"),
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

    def handle_event(self, envelope: EventEnvelope) -> HandlerResult:
        try:
            self.repository.record_consumed_event(envelope)
        except Exception as exc:  # pragma: no cover - defensive boundary for broker callback
            return HandlerResult.fatal_error(str(exc))
        return HandlerResult.success()

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
            eventId=prefixed_uuid("evt"),
            eventType=event_type,
            occurredAt=rfc3339_utc(occurred_at or utc_now()),
            correlationId=correlation_id if correlation_id and correlation_id.startswith("corr-") else prefixed_uuid("corr"),
            causationId=causation_id if causation_id and (causation_id.startswith("cmd-") or causation_id.startswith("evt-")) else prefixed_uuid("cmd"),
            producer=PRODUCER,
            schemaVersion=1,
            payload=dict(payload),
        )
        if self.publisher is not None:
            self.publisher.publish(envelope)
        return envelope
