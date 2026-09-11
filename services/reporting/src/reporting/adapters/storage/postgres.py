from __future__ import annotations

import hashlib
import json
import logging
from collections.abc import Mapping, Sequence
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from typing import Any

from reporting.application.service import (
    PRODUCER,
    RebuildRun,
    ReportingApplicationService,
    dashboard_consumes_event,
    default_repository,
    operational_event_from_envelope,
    reporting_applies_event_type,
    rfc3339_utc,
    utc_now,
)
from reporting.domain import (
    AnomalyDetected,
    AnomalyDetector,
    AnomalySeverity,
    ContextCountReport,
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
    RevenueItem,
    RevenueReport,
    RouteMetrics,
)
from reporting.ids import prefixed_uuid7
from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.storage import OutboxAppender, ProcessedEventsGuard, SnapshotRepository

try:  # pragma: no cover - exercised only with psycopg installed at runtime
    from psycopg.types.json import Jsonb
except Exception:  # pragma: no cover
    Jsonb = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)

_PAYMENT_CAPTURED_EVENT_TYPES = ("PaymentCaptured", "RevenueRecognized", "PaymentSucceeded")

# How far back the anomaly rules can see. REVENUE_DROP compares the last hour
# against the same hour yesterday, which is the longest window any rule reads;
# the rest look back an hour or less. Loading more than this cannot change a
# verdict, so the write path stops at the boundary and the read endpoints, which
# report over all of history, keep loading everything.
_DETECTION_WINDOW = timedelta(days=1, hours=1)


def _json_payload(value: Mapping[str, Any]) -> Any:
    return Jsonb(dict(value)) if Jsonb is not None else json.dumps(dict(value), separators=(",", ":"))


def _dt(value: datetime | str | None) -> str | None:
    if value is None:
        return None
    if isinstance(value, str):
        return value
    return rfc3339_utc(value)


def _parse_dt(value: str | None) -> datetime | None:
    if value is None:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


def _metric_to_json(metric: MetricDefinition) -> dict[str, Any]:
    return {
        "metricId": metric.metric_id,
        "name": metric.name,
        "description": metric.description,
        "owner": metric.owner,
        "category": metric.category.value,
        "granularity": metric.granularity.value,
        "version": metric.version,
        "expression": metric.expression,
        "status": metric.status.value,
        "publishedAt": _dt(metric.published_at),
        "dependsOnMetricIds": list(metric.depends_on_metric_ids),
        "sourceLineage": list(metric.source_lineage),
        "deprecatedAt": _dt(metric.deprecated_at),
    }


def _metric_from_json(data: Mapping[str, Any] | str) -> MetricDefinition:
    if isinstance(data, str):
        data = json.loads(data)
    return MetricDefinition(
        metric_id=str(data["metricId"]),
        name=str(data["name"]),
        description=str(data.get("description", "")),
        owner=str(data["owner"]),
        category=MetricCategory(str(data["category"])),
        granularity=MetricGranularity(str(data["granularity"])),
        version=str(data["version"]),
        expression=str(data["expression"]),
        status=MetricStatus(str(data.get("status", MetricStatus.DRAFT.value))),
        published_at=_parse_dt(data.get("publishedAt")),
        depends_on_metric_ids=tuple(str(item) for item in data.get("dependsOnMetricIds", ())),
        source_lineage=tuple(str(item) for item in data.get("sourceLineage", ())),
        deprecated_at=_parse_dt(data.get("deprecatedAt")),
    )


def _ref_to_json(ref: MetricRef) -> dict[str, str]:
    return {"metricId": ref.metric_id, "version": ref.version}


def _ref_from_json(data: Mapping[str, Any]) -> MetricRef:
    return MetricRef(str(data["metricId"]), str(data["version"]))


def _snapshot_to_json(snapshot: ReadModelSnapshot | None) -> dict[str, Any] | None:
    if snapshot is None:
        return None
    return {
        "rebuildId": snapshot.rebuild_id,
        "rebuiltAt": _dt(snapshot.rebuilt_at),
        "metrics": [_ref_to_json(ref) for ref in snapshot.metrics],
        "eventCount": snapshot.event_count,
        "digest": snapshot.digest,
    }


def _snapshot_from_json(data: Mapping[str, Any] | None) -> ReadModelSnapshot | None:
    if data is None:
        return None
    return ReadModelSnapshot(str(data["rebuildId"]), _parse_dt(str(data["rebuiltAt"])) or utc_now(), tuple(_ref_from_json(item) for item in data.get("metrics", ())), int(data["eventCount"]), str(data["digest"]))


def _dashboard_to_json(dashboard: DashboardReadModel) -> dict[str, Any]:
    return {
        "dashboardId": dashboard.dashboard_id,
        "name": dashboard.name,
        "description": dashboard.description,
        "status": dashboard.status.value,
        "metrics": [_ref_to_json(ref) for ref in dashboard.metrics],
        "currentSnapshot": _snapshot_to_json(dashboard.current_snapshot),
        "lastBuiltAt": _dt(dashboard.last_built_at),
        "sourceEvents": list(dashboard.source_events),
    }


def _dashboard_from_json(data: Mapping[str, Any] | str) -> DashboardReadModel:
    if isinstance(data, str):
        data = json.loads(data)
    return DashboardReadModel(
        dashboard_id=str(data["dashboardId"]),
        name=str(data["name"]),
        description=str(data.get("description", "")),
        status=ReadModelStatus(str(data.get("status", ReadModelStatus.BUILDING.value))),
        metrics=tuple(_ref_from_json(item) for item in data.get("metrics", ())),
        current_snapshot=_snapshot_from_json(data.get("currentSnapshot")),
        last_built_at=_parse_dt(data.get("lastBuiltAt")),
        source_events=tuple(str(item) for item in data.get("sourceEvents", ())),
    )


def _row_datetime(value: datetime | str) -> datetime:
    if isinstance(value, datetime):
        return value.astimezone(UTC) if value.tzinfo is not None else value.replace(tzinfo=UTC)
    return datetime.fromisoformat(str(value).replace("Z", "+00:00")).astimezone(UTC)


def _row_date(value: date | datetime | str | None, occurred_at: datetime) -> date | None:
    if value is None:
        return occurred_at.date()
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    return date.fromisoformat(str(value)[:10])


def _flags_from_event(event: OperationalEvent) -> dict[str, Any]:
    return {
        "paymentFailed": event.payment_failed,
        "refunded": event.refunded,
        "scalperBlocked": event.scalper_blocked,
        "distanceKm": None if event.distance_km is None else str(event.distance_km),
        "ancillaryAttached": event.ancillary_attached,
        "insuranceAttached": event.insurance_attached,
        "sourceContext": event.source_context,
        "anomalySignal": event.anomaly_signal,
    }


def _flags(data: Mapping[str, Any] | str | None) -> Mapping[str, Any]:
    if data is None:
        return {}
    if hasattr(data, "obj"):
        data = data.obj
    if isinstance(data, str):
        return json.loads(data)
    return data


def _bool_flag(flags: Mapping[str, Any], key: str) -> bool:
    value = flags.get(key)
    if isinstance(value, str):
        return value.lower() in {"1", "true", "yes"}
    return bool(value)


def _decimal_flag(flags: Mapping[str, Any], key: str) -> Decimal | None:
    value = flags.get(key)
    if value is None:
        return None
    return Decimal(str(value))


def _event_from_row(row: Sequence[Any]) -> OperationalEvent:
    occurred_at = _row_datetime(row[2])
    flags = _flags(row[13])
    amount = None if row[6] is None else Money(Decimal(str(row[6])), str(row[7] or "USD"))
    return OperationalEvent(
        event_id=str(row[0]),
        event_type=str(row[1]),
        occurred_at=occurred_at,
        route_id=None if row[3] is None else str(row[3]),
        service_date=_row_date(row[4], occurred_at),
        seat_class=None if row[5] is None else str(row[5]),
        amount=amount,
        channel=None if row[8] is None else str(row[8]),
        passenger_type=None if row[9] is None else str(row[9]),
        capacity=None if row[10] is None else int(row[10]),
        confirmed=None if row[11] is None else int(row[11]),
        booking_latency_ms=None if row[12] is None else int(row[12]),
        payment_failed=_bool_flag(flags, "paymentFailed"),
        refunded=_bool_flag(flags, "refunded"),
        scalper_blocked=_bool_flag(flags, "scalperBlocked"),
        distance_km=_decimal_flag(flags, "distanceKm"),
        ancillary_attached=_bool_flag(flags, "ancillaryAttached"),
        insurance_attached=_bool_flag(flags, "insuranceAttached"),
        source_context=None if flags.get("sourceContext") is None else str(flags.get("sourceContext")),
        anomaly_signal=_bool_flag(flags, "anomalySignal"),
    )


class PostgresReportingApplicationService:
    _next_rebuild_event_count = staticmethod(ReportingApplicationService._next_rebuild_event_count)

    def __init__(self, pool: Any, outbox: OutboxAppender | None = None) -> None:
        self._pool = pool
        self._outbox = outbox or OutboxAppender()
        self._processed = ProcessedEventsGuard()
        self._metrics = SnapshotRepository("metric_definition_snapshots")
        self._dashboards = SnapshotRepository("dashboard_read_model_snapshots")
        self._detector = AnomalyDetector()
        self._seed_defaults()

    def _seed_defaults(self) -> None:
        defaults = default_repository()
        with self._pool.connection() as conn:
            with conn.transaction():
                for metric in defaults.metrics.values():
                    if self._metrics.get(conn, metric.metric_id) is None:
                        self._metrics.save(conn, metric.metric_id, _metric_to_json(metric))
                for dashboard in defaults.dashboards.values():
                    if self._dashboards.get(conn, dashboard.dashboard_id) is None:
                        self._dashboards.save(conn, dashboard.dashboard_id, _dashboard_to_json(dashboard))
                for runs in defaults.rebuild_runs.values():
                    for run in runs:
                        self._save_rebuild_run(conn, run)

    def list_metrics(self, *, category: MetricCategory | None, limit: int, offset: int) -> tuple[list[MetricDefinition], int]:
        with self._pool.connection() as conn:
            if category is None:
                total = int(conn.execute("SELECT count(*) FROM metric_definition_snapshots").fetchone()[0])
                rows = conn.execute("SELECT data FROM metric_definition_snapshots ORDER BY id LIMIT %s OFFSET %s", (limit, offset)).fetchall()
            else:
                total = int(conn.execute("SELECT count(*) FROM metric_definition_snapshots WHERE data->>'category' = %s", (category.value,)).fetchone()[0])
                rows = conn.execute("SELECT data FROM metric_definition_snapshots WHERE data->>'category' = %s ORDER BY id LIMIT %s OFFSET %s", (category.value, limit, offset)).fetchall()
        return [_metric_from_json(row[0]) for row in rows], total

    def get_metric(self, metric_id: str) -> MetricDefinition | None:
        with self._pool.connection() as conn:
            snap = self._metrics.get(conn, metric_id)
        return None if snap is None else _metric_from_json(snap[1])

    def get_dashboard(self, dashboard_id: str) -> DashboardReadModel | None:
        with self._pool.connection() as conn:
            snap = self._dashboards.get(conn, dashboard_id)
        return None if snap is None else _dashboard_from_json(snap[1])

    def list_rebuilds(self, dashboard_id: str, *, limit: int, offset: int) -> tuple[list[RebuildRun], int]:
        with self._pool.connection() as conn:
            total = int(conn.execute("SELECT count(*) FROM reporting_rebuild_runs WHERE dashboard_id = %s", (dashboard_id,)).fetchone()[0])
            rows = conn.execute("SELECT rebuild_id, dashboard_id, rebuilt_at, status, event_count, digest FROM reporting_rebuild_runs WHERE dashboard_id = %s ORDER BY rebuilt_at DESC LIMIT %s OFFSET %s", (dashboard_id, limit, offset)).fetchall()
        return [RebuildRun(str(r[0]), str(r[1]), r[2].astimezone(UTC), str(r[3]), int(r[4]), str(r[5])) for r in rows], total

    def handle_event(self, envelope: EventEnvelope):
        from train_ticket_platform.messaging import HandlerResult
        if not reporting_applies_event_type(envelope.eventType):
            return HandlerResult.success()
        try:
            with self._pool.connection() as conn:
                with conn.transaction():
                    if not self._processed.try_mark_processed(conn, envelope.eventId, f"events:{envelope.producer}"):
                        return HandlerResult.success()
                    normalized = operational_event_from_envelope(envelope)
                    self._save_metric_event(conn, normalized)
                    if normalized.event_type in _PAYMENT_CAPTURED_EVENT_TYPES:
                        self._refresh_revenue_views(conn)
                    aggregator = self._load_aggregator(conn, since=utc_now() - _DETECTION_WINDOW)
                    newly_detected = self._detector.evaluate(aggregator)
                    active = self._detector.list_active()
                    self._resolve_inactive_anomalies(conn, [anomaly.rule_id for anomaly in active])
                    for anomaly in active:
                        changed = self._save_anomaly(conn, anomaly)
                        if changed and any(item.rule_id == anomaly.rule_id for item in newly_detected):
                            self._append_anomaly_actions(conn, anomaly, envelope)
                    dashboards = self._all_dashboards(conn)
                    for dashboard, version in dashboards:
                        if dashboard_consumes_event(dashboard, envelope.eventType):
                            stale = dashboard.mark_stale()
                            new_version = self._dashboards.save(conn, stale.dashboard_id, _dashboard_to_json(stale), version)
                            self._maybe_rebuild(conn, stale, new_version, envelope)
        except Exception as exc:
            # Log with the traceback. This is the handler production actually uses
            # -- the in-memory ReportingApplicationService has its own, and probing
            # THAT one succeeded, which is why the failure looked unreproducible.
            #
            # Without a stack the only evidence was the bare text "'str' object
            # cannot be interpreted as an integer", repeated for every event on
            # every subscribed stream: 13,511 pending messages, dash-revenue stuck
            # in `building` since 2026-07-05, reporting_revenue_by_route empty.
            logger.exception(
                "reporting handler FAILED event=%s eventId=%s producer=%s -- returning "
                "TRANSIENT_ERROR, so this message stays pending and will be redelivered",
                envelope.eventType,
                envelope.eventId,
                getattr(envelope, "producer", "?"),
            )
            return HandlerResult.transient_error(f"{type(exc).__name__}: {exc}")
        return HandlerResult.success()

    def _save_metric_event(self, conn: Any, event: OperationalEvent) -> None:
        conn.execute(
            """
            INSERT INTO reporting_metric_events(
              event_id, event_type, occurred_at, route_id, service_date, seat_class,
              amount, currency, channel, passenger_type, capacity, confirmed,
              booking_latency_ms, flags
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (event_id) DO NOTHING
            """,
            (
                event.event_id,
                event.event_type,
                event.occurred_at,
                event.route_id,
                event.service_date,
                event.seat_class,
                None if event.amount is None else event.amount.amount,
                None if event.amount is None else event.amount.currency,
                event.channel,
                event.passenger_type,
                event.capacity,
                event.confirmed,
                event.booking_latency_ms,
                _json_payload(_flags_from_event(event)),
            ),
        )

    # The revenue views filter on the payment-capture types in
    # 001_reporting_storage.sql, so no other event type can change what a refresh
    # produces.
    def _refresh_revenue_views(self, conn: Any) -> None:
        for view_name in (
            "reporting_revenue_by_route",
            "reporting_revenue_by_seat_class",
            "reporting_revenue_breakdowns",
        ):
            conn.execute(f"REFRESH MATERIALIZED VIEW {view_name}")

    def _load_aggregator(self, conn: Any, *, since: datetime | None = None) -> MetricAggregator:
        # The window is spliced into the WHERE clause rather than passed as a
        # parameter that may be NULL: `WHERE %s IS NULL OR occurred_at >= %s`
        # cannot use idx_reporting_metric_events_occurred and plans a Seq Scan.
        where = "" if since is None else "WHERE occurred_at >= %s"
        params = () if since is None else (since,)
        rows = conn.execute(
            f"""
            SELECT event_id, event_type, occurred_at, route_id, service_date, seat_class,
                   amount, currency, channel, passenger_type, capacity, confirmed,
                   booking_latency_ms, flags
            FROM reporting_metric_events
            {where}
            ORDER BY occurred_at, event_id
            """,
            params,
        ).fetchall()
        # The aggregator holds a single currency and rejects any event that
        # disagrees, so it has to be told which one the stored events are in
        # rather than assuming its own default. The system quotes in CNY, and
        # every RevenueRecognized event failed and was redelivered forever.
        currency = next((str(row[7]) for row in rows if row[7]), None)
        aggregator = MetricAggregator() if currency is None else MetricAggregator(currency=currency)
        for row in rows:
            aggregator.record(_event_from_row(row))
        return aggregator

    def _all_dashboards(self, conn: Any) -> list[tuple[DashboardReadModel, int]]:
        return [(_dashboard_from_json(row[2]), int(row[1])) for row in conn.execute("SELECT id, version, data FROM dashboard_read_model_snapshots").fetchall()]

    def _processed_count(self, conn: Any) -> int:
        return int(conn.execute("SELECT count(*) FROM processed_events").fetchone()[0])

    def _maybe_rebuild(self, conn: Any, dashboard: DashboardReadModel, version: int, envelope: EventEnvelope) -> None:
        if dashboard.status is not ReadModelStatus.STALE:
            return
        now = utc_now()
        event_count = self._next_rebuild_event_count(dashboard, self._processed_count(conn))
        digest = "sha256-" + hashlib.sha256(f"{dashboard.dashboard_id}:{event_count}:{rfc3339_utc(now)}".encode()).hexdigest()[:16]
        rebuild_id = prefixed_uuid7("rebuild")
        rebuilt = dashboard.rebuild(rebuild_id, now, event_count, digest)
        self._dashboards.save(conn, rebuilt.dashboard_id, _dashboard_to_json(rebuilt), version)
        self._save_rebuild_run(conn, RebuildRun(rebuild_id, rebuilt.dashboard_id, now, "COMPLETED", event_count, digest))
        self._outbox.append(conn, self._read_model_rebuilt_envelope(rebuilt.dashboard_id, rebuild_id, now, event_count, digest, envelope))

    def _save_rebuild_run(self, conn: Any, run: RebuildRun) -> None:
        conn.execute("INSERT INTO reporting_rebuild_runs(rebuild_id, dashboard_id, rebuilt_at, status, event_count, digest) VALUES (%s, %s, %s, %s, %s, %s) ON CONFLICT (rebuild_id) DO NOTHING", (run.rebuild_id, run.dashboard_id, run.rebuilt_at, run.status, run.event_count, run.digest))

    def _read_model_rebuilt_envelope(self, dashboard_id: str, rebuild_id: str, rebuilt_at: datetime, event_count: int, digest: str, source: EventEnvelope) -> EventEnvelope:
        return EventEnvelope(eventId=prefixed_uuid7("evt"), eventType="ReadModelRebuilt", occurredAt=rfc3339_utc(rebuilt_at), correlationId=source.correlationId if source.correlationId and source.correlationId.startswith("corr-") else prefixed_uuid7("corr"), causationId=source.eventId, producer=PRODUCER, schemaVersion=1, payload={"dashboardId": dashboard_id, "rebuildId": rebuild_id, "rebuiltAt": rfc3339_utc(rebuilt_at), "eventCount": event_count, "digest": digest})

    def _save_anomaly(self, conn: Any, anomaly: AnomalyDetected) -> bool:
        row = conn.execute(
            """
            INSERT INTO reporting_anomalies(rule_id, current_value, threshold, severity, detected_at, resolved_at)
            VALUES (%s, %s, %s, %s, %s, NULL)
            ON CONFLICT (rule_id) DO UPDATE SET
              current_value = EXCLUDED.current_value,
              threshold = EXCLUDED.threshold,
              severity = EXCLUDED.severity,
              detected_at = EXCLUDED.detected_at,
              resolved_at = NULL
            WHERE reporting_anomalies.current_value IS DISTINCT FROM EXCLUDED.current_value
               OR reporting_anomalies.threshold IS DISTINCT FROM EXCLUDED.threshold
               OR reporting_anomalies.severity IS DISTINCT FROM EXCLUDED.severity
               OR reporting_anomalies.resolved_at IS NOT NULL
            RETURNING rule_id
            """,
            (anomaly.rule_id, anomaly.current_value, anomaly.threshold, anomaly.severity.value, anomaly.detected_at),
        ).fetchone()
        return row is not None

    def _resolve_inactive_anomalies(self, conn: Any, active_rule_ids: Sequence[str]) -> None:
        now = utc_now()
        if not active_rule_ids:
            conn.execute("UPDATE reporting_anomalies SET resolved_at = %s WHERE resolved_at IS NULL", (now,))
            return
        placeholders = ", ".join(["%s"] * len(active_rule_ids))
        conn.execute(
            f"UPDATE reporting_anomalies SET resolved_at = %s WHERE resolved_at IS NULL AND rule_id NOT IN ({placeholders})",
            (now, *active_rule_ids),
        )

    def _append_anomaly_actions(self, conn: Any, anomaly: AnomalyDetected, source: EventEnvelope) -> None:
        logger.log(
            logging.ERROR if anomaly.severity is AnomalySeverity.CRITICAL else logging.WARNING,
            "Reporting anomaly detected rule_id=%s severity=%s current_value=%s threshold=%s",
            anomaly.rule_id,
            anomaly.severity.value,
            anomaly.current_value,
            anomaly.threshold,
        )
        self._outbox.append(conn, self._anomaly_detected_envelope(anomaly, source))
        if anomaly.urgent:
            self._outbox.append(conn, self._urgent_notification_envelope(anomaly, source))

    def operational_metrics(self, at: datetime | None = None) -> MetricSnapshot:
        with self._pool.connection() as conn:
            return self._load_aggregator(conn).snapshot(at)

    def route_metrics(self, at: datetime | None = None) -> tuple[RouteMetrics, ...]:
        with self._pool.connection() as conn:
            return self._load_aggregator(conn).route_metrics(at)

    def context_count_report(self, group_by: str = "source_context", limit: int = 20, at: datetime | None = None) -> ContextCountReport:
        with self._pool.connection() as conn:
            return self._load_aggregator(conn).context_count_report(group_by=group_by, limit=limit, at=at)

    def revenue_report(self, group_by: str = "route", limit: int = 20, at: datetime | None = None) -> RevenueReport:
        if group_by not in {"route", "seat_class", "channel", "passenger_type", "time_period"}:
            with self._pool.connection() as conn:
                return self._load_aggregator(conn).revenue_report(group_by=group_by, limit=limit, at=at)
        with self._pool.connection() as conn:
            rows = conn.execute(
                """
                SELECT dimension_value, currency, revenue, payment_count, distance_km,
                       ancillary_count, insurance_count
                FROM reporting_revenue_breakdowns
                WHERE dimension = %s
                ORDER BY revenue DESC, dimension_value
                LIMIT %s
                """,
                (group_by, limit),
            ).fetchall()
        items = tuple(self._revenue_item_from_view(group_by, row) for row in rows)
        total = sum((item.revenue.amount for item in items), Decimal("0.00"))
        return RevenueReport(at or utc_now(), group_by, items, Money(total, items[0].revenue.currency if items else "USD"))

    @staticmethod
    def _revenue_item_from_view(group_by: str, row: Sequence[Any]) -> RevenueItem:
        value = str(row[0] or "unknown")
        currency = str(row[1] or "USD")
        revenue = Decimal(str(row[2] or "0.00"))
        count = int(row[3] or 0)
        distance = Decimal(str(row[4] or "0"))
        ancillary_count = int(row[5] or 0)
        insurance_count = int(row[6] or 0)
        return RevenueItem(
            dimension=group_by,
            value=value,
            revenue=Money(revenue, currency),
            count=count,
            yield_per_km=Decimal("0.00") if distance == 0 else (revenue / distance).quantize(Decimal("0.01")),
            ancillary_attach_rate=0.0 if count == 0 else ancillary_count / count,
            insurance_attach_rate=0.0 if count == 0 else insurance_count / count,
        )

    def list_anomalies(self) -> tuple[AnomalyDetected, ...]:
        with self._pool.connection() as conn:
            rows = conn.execute(
                """
                SELECT rule_id, current_value, threshold, severity, detected_at
                FROM reporting_anomalies
                WHERE resolved_at IS NULL
                ORDER BY detected_at DESC
                """
            ).fetchall()
        return tuple(
            AnomalyDetected(
                str(row[0]),
                Decimal(str(row[1])),
                Decimal(str(row[2])),
                AnomalySeverity(str(row[3])),
                _row_datetime(row[4]),
                str(row[0]) in {"REVENUE_DROP", "ERROR_RATE_SPIKE"},
            )
            for row in rows
        )

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

    def _anomaly_detected_envelope(self, anomaly: AnomalyDetected, source: EventEnvelope) -> EventEnvelope:
        return EventEnvelope(
            eventId=prefixed_uuid7("evt"),
            eventType="AnomalyDetected",
            occurredAt=rfc3339_utc(anomaly.detected_at),
            correlationId=source.correlationId if source.correlationId and source.correlationId.startswith("corr-") else prefixed_uuid7("corr"),
            causationId=source.eventId,
            producer=PRODUCER,
            schemaVersion=1,
            payload={
                "ruleId": anomaly.rule_id,
                "currentValue": str(anomaly.current_value),
                "threshold": str(anomaly.threshold),
                "severity": anomaly.severity.value,
                "detectedAt": rfc3339_utc(anomaly.detected_at),
            },
        )

    def _urgent_notification_envelope(self, anomaly: AnomalyDetected, source: EventEnvelope) -> EventEnvelope:
        return EventEnvelope(
            eventId=prefixed_uuid7("evt"),
            eventType="UrgentNotificationRequested",
            occurredAt=rfc3339_utc(anomaly.detected_at),
            correlationId=source.correlationId if source.correlationId and source.correlationId.startswith("corr-") else prefixed_uuid7("corr"),
            causationId=source.eventId,
            producer=PRODUCER,
            schemaVersion=1,
            payload={
                "channel": "ops-alert",
                "reason": "reporting_anomaly",
                "ruleId": anomaly.rule_id,
                "severity": anomaly.severity.value,
                "currentValue": str(anomaly.current_value),
                "threshold": str(anomaly.threshold),
            },
        )

    def publish_domain_event(self, *, event_type: str, payload: Mapping[str, Any], correlation_id: str | None, causation_id: str | None, occurred_at: datetime | None = None) -> EventEnvelope:
        envelope = EventEnvelope(eventId=prefixed_uuid7("evt"), eventType=event_type, occurredAt=rfc3339_utc(occurred_at or utc_now()), correlationId=correlation_id if correlation_id and correlation_id.startswith("corr-") else prefixed_uuid7("corr"), producer=PRODUCER, schemaVersion=1, payload=dict(payload), causationId=causation_id if causation_id and (causation_id.startswith("cmd-") or causation_id.startswith("evt-")) else None)
        with self._pool.connection() as conn:
            with conn.transaction():
                self._outbox.append(conn, envelope)
        return envelope
