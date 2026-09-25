from __future__ import annotations

import hashlib
import json
import logging
import threading
from collections.abc import Mapping, Sequence
from dataclasses import replace
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
    project_revenue_facts,
    reporting_applies_event_type,
    rfc3339_utc,
    utc_now,
)
from reporting.domain import (
    AnomalyDetected,
    AnomalyDetector,
    AnomalyRuleId,
    AnomalySeverity,
    ContextEventRollup,
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
    RevenueFactIndex,
    RevenueReport,
    RouteMetrics,
)
from reporting.ids import prefixed_uuid7
from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.storage import OutboxAppender, ProcessedEventsGuard, SnapshotRepository

from psycopg.types.json import Jsonb

logger = logging.getLogger(__name__)

_PAYMENT_CAPTURED_EVENT_TYPES = ("PaymentCaptured",)
_ORDER_CREATED_EVENT_TYPES = ("JourneyOrderCreated", "OrderCreated")
_SEARCH_EVENT_TYPES = ("TripSearched", "SearchPerformed", "OfferSearchRequested")
_PAYMENT_FAILED_EVENT_TYPES = ("PaymentFailed", "PaymentDeclined", "PaymentCaptureFailed")
_REFUND_EVENT_TYPES = ("RefundSettled", "RefundCompleted", "RefundIssued", "RevenueRecognitionReversed")
_RISK_BLOCK_EVENT_TYPES = ("ScalperBlocked", "RiskBookingBlocked")
_REVENUE_PROJECTION_EVENT_TYPES = frozenset({
    "PaymentCaptured",
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
})
_REVENUE_REFRESH_INTERVAL = 30.0

def _json_payload(value: Mapping[str, Any]) -> Any:
    return Jsonb(dict(value))


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
        "businessRef": event.business_ref,
        "paymentIntentId": event.payment_intent_id,
        "paymentPurpose": event.payment_purpose,
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
        business_ref=None if flags.get("businessRef") is None else str(flags.get("businessRef")),
        payment_intent_id=None if flags.get("paymentIntentId") is None else str(flags.get("paymentIntentId")),
        payment_purpose=None if flags.get("paymentPurpose") is None else str(flags.get("paymentPurpose")),
    )


class _PostgresRevenueFactIndex:
    def __init__(self, conn: Any) -> None:
        self._conn = conn
        self.changed = False

    def _record(self, cursor: Any) -> None:
        self.changed = self.changed or cursor.rowcount > 0

    def record_capture(self, event_id: str, business_ref: str, payment_intent_id: str, payment_purpose: str, occurred_at: datetime) -> None:
        if not event_id.strip():
            return
        business_ref = business_ref.strip() or f"unresolved:{event_id}"
        self._record(self._conn.execute(
            """
            INSERT INTO reporting_capture_references(event_id,business_ref,payment_intent_id,payment_purpose,source_occurred_at)
            VALUES (%s,%s,NULLIF(%s,''),NULLIF(%s,''),%s)
            ON CONFLICT(event_id) DO UPDATE SET
              business_ref=EXCLUDED.business_ref,
              payment_intent_id=EXCLUDED.payment_intent_id,
              payment_purpose=EXCLUDED.payment_purpose,
              source_occurred_at=EXCLUDED.source_occurred_at
            WHERE reporting_capture_references.source_occurred_at < EXCLUDED.source_occurred_at
            """,
            (event_id, business_ref, payment_intent_id, payment_purpose, occurred_at),
        ))

    def record_order(self, order_id: str, routes: Any, travelers: Any, occurred_at: datetime) -> None:
        if not order_id.strip():
            return
        route_rows = [(order_id, route_id, service_date, occurred_at) for route_id, service_date in routes if str(route_id).strip()]
        if route_rows:
            with self._conn.cursor() as cursor:
                cursor.executemany(
                    """
                    INSERT INTO reporting_order_routes(order_id,route_id,service_date,source_occurred_at)
                    VALUES (%s,%s,%s,%s)
                    ON CONFLICT(order_id,route_id) DO UPDATE SET
                      service_date=EXCLUDED.service_date,source_occurred_at=EXCLUDED.source_occurred_at
                    WHERE reporting_order_routes.source_occurred_at < EXCLUDED.source_occurred_at
                    """,
                    route_rows,
                )
                self._record(cursor)
        traveler_rows = [(order_id, traveler_ref, traveler_type, occurred_at) for traveler_ref, traveler_type in travelers if str(traveler_ref).strip() and str(traveler_type).strip()]
        if traveler_rows:
            with self._conn.cursor() as cursor:
                cursor.executemany(
                    """
                    INSERT INTO reporting_order_travelers(order_id,traveler_ref,traveler_type,source_occurred_at)
                    VALUES (%s,%s,%s,%s)
                    ON CONFLICT(order_id,traveler_ref) DO UPDATE SET
                      traveler_type=EXCLUDED.traveler_type,source_occurred_at=EXCLUDED.source_occurred_at
                    WHERE reporting_order_travelers.source_occurred_at < EXCLUDED.source_occurred_at
                    """,
                    traveler_rows,
                )
                self._record(cursor)
        self.record_business_order(order_id, order_id, (), (), occurred_at)

    def record_business_order(self, business_ref: str, order_id: str, segment_refs: Any, traveler_refs: Any, occurred_at: datetime) -> None:
        if not business_ref.strip() or not order_id.strip():
            return
        self._record(self._conn.execute(
            """
            INSERT INTO reporting_business_scopes(business_ref,order_id,segment_refs,traveler_refs,source_occurred_at)
            VALUES (%s,%s,%s,%s,%s)
            ON CONFLICT(business_ref) DO UPDATE SET
              order_id=EXCLUDED.order_id,
              segment_refs=CASE WHEN cardinality(EXCLUDED.segment_refs)>0 THEN EXCLUDED.segment_refs ELSE reporting_business_scopes.segment_refs END,
              traveler_refs=CASE WHEN cardinality(EXCLUDED.traveler_refs)>0 THEN EXCLUDED.traveler_refs ELSE reporting_business_scopes.traveler_refs END,
              source_occurred_at=EXCLUDED.source_occurred_at
            WHERE reporting_business_scopes.source_occurred_at <= EXCLUDED.source_occurred_at
            """,
            (business_ref, order_id, list(segment_refs), list(traveler_refs), occurred_at),
        ))

    def record_seat_class(self, order_id: str, segment_ref: str, traveler_ref: str, seat_class: str, occurred_at: datetime, source_priority: int = 0) -> None:
        if not all(value.strip() for value in (order_id, segment_ref, traveler_ref, seat_class)):
            return
        self._record(self._conn.execute(
            """
            INSERT INTO reporting_seat_classes(order_id,segment_ref,traveler_ref,seat_class,source_priority,source_occurred_at)
            VALUES (%s,%s,%s,%s,%s,%s)
            ON CONFLICT(order_id,segment_ref,traveler_ref) DO UPDATE SET
              seat_class=EXCLUDED.seat_class,source_priority=EXCLUDED.source_priority,source_occurred_at=EXCLUDED.source_occurred_at
            WHERE (reporting_seat_classes.source_priority,reporting_seat_classes.source_occurred_at)
               < (EXCLUDED.source_priority,EXCLUDED.source_occurred_at)
            """,
            (order_id, segment_ref, traveler_ref, seat_class, source_priority, occurred_at),
        ))

    def record_attachment(self, order_id: str, attachment_type: str, occurred_at: datetime) -> None:
        if not order_id.strip() or attachment_type not in {"ANCILLARY", "INSURANCE"}:
            return
        self._record(self._conn.execute(
            """
            INSERT INTO reporting_order_attachments(order_id,attachment_type,source_occurred_at)
            VALUES (%s,%s,%s)
            ON CONFLICT(order_id,attachment_type) DO UPDATE SET
              source_occurred_at=EXCLUDED.source_occurred_at
            WHERE reporting_order_attachments.source_occurred_at < EXCLUDED.source_occurred_at
            """,
            (order_id, attachment_type, occurred_at),
        ))


class PostgresReportingApplicationService:
    _next_rebuild_event_count = staticmethod(ReportingApplicationService._next_rebuild_event_count)

    def __init__(self, pool: Any, outbox: OutboxAppender | None = None) -> None:
        self._pool = pool
        self._outbox = outbox or OutboxAppender()
        self._processed = ProcessedEventsGuard()
        self._metrics = SnapshotRepository("metric_definition_snapshots")
        self._dashboards = SnapshotRepository("dashboard_read_model_snapshots")
        self._detector = AnomalyDetector()
        self._refresh_stop = threading.Event()
        self._refresh_thread: threading.Thread | None = None
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
                    metric_inserted = self._save_metric_event(conn, normalized)
                    fact_index = _PostgresRevenueFactIndex(conn)
                    project_revenue_facts(fact_index, envelope)
                    if not metric_inserted and not fact_index.changed:
                        return HandlerResult.success()
                    conn.execute(
                        "INSERT INTO reporting_projection_refresh_requests(event_type,generation,source_event) VALUES (%s,1,%s) "
                        "ON CONFLICT(event_type) DO UPDATE SET generation=reporting_projection_refresh_requests.generation+1, "
                        "source_event=EXCLUDED.source_event,updated_at=now()",
                        (envelope.eventType, Jsonb(envelope.to_dict())),
                    )
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

    def start_projection_refresh(self) -> None:
        if self._refresh_thread is not None and self._refresh_thread.is_alive():
            return
        self._refresh_stop.clear()
        self._refresh_thread = threading.Thread(target=self._refresh_loop, name="reporting-projections", daemon=True)
        self._refresh_thread.start()

    def stop_projection_refresh(self) -> None:
        self._refresh_stop.set()
        if self._refresh_thread is not None:
            self._refresh_thread.join(timeout=60)

    def _refresh_loop(self) -> None:
        while not self._refresh_stop.is_set():
            try:
                self.refresh_pending_projections()
            except Exception:
                logger.exception("reporting projection refresh failed; durable requests remain pending")
            self._refresh_stop.wait(_REVENUE_REFRESH_INTERVAL)

    def refresh_pending_projections(self) -> int:
        with self._pool.connection() as conn:
            with conn.transaction():
                if not conn.execute("SELECT pg_try_advisory_xact_lock(hashtextextended('reporting:projection-refresh',0))").fetchone()[0]:
                    return 0
                pending = conn.execute(
                    "SELECT event_type,generation,refreshed_generation,source_event "
                    "FROM reporting_projection_refresh_requests WHERE generation>refreshed_generation ORDER BY event_type"
                ).fetchall()
                if not pending:
                    return 0
                source = EventEnvelope.from_json_dict(pending[-1][3])
                if any(row[0] in _REVENUE_PROJECTION_EVENT_TYPES for row in pending):
                    self._refresh_revenue_views(conn)
                active = self._evaluate_anomalies(conn, utc_now())
                self._resolve_inactive_anomalies(conn, [anomaly.rule_id for anomaly in active])
                for anomaly in active:
                    changed = self._save_anomaly(conn, anomaly)
                    if changed:
                        self._append_anomaly_actions(conn, anomaly, source)
                for dashboard, version in self._all_dashboards(conn, for_update=True):
                    delta = sum(row[1] - row[2] for row in pending if dashboard_consumes_event(dashboard, row[0]))
                    if delta:
                        count = (dashboard.current_snapshot.event_count if dashboard.current_snapshot else 0) + delta
                        self._maybe_rebuild(conn, dashboard.mark_stale(), version, source, count)
                for event_type, generation, _, _ in pending:
                    conn.execute("UPDATE reporting_projection_refresh_requests SET refreshed_generation=%s WHERE event_type=%s", (generation, event_type))
        return sum(row[1] - row[2] for row in pending)

    def _save_metric_event(self, conn: Any, event: OperationalEvent) -> bool:
        row = conn.execute(
            """
            INSERT INTO reporting_metric_events(
              event_id, event_type, occurred_at, route_id, service_date, seat_class,
              amount, currency, channel, passenger_type, capacity, confirmed,
              booking_latency_ms, flags
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (event_id) DO NOTHING RETURNING event_id
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
        ).fetchone()
        return row is not None

    def _refresh_revenue_views(self, conn: Any) -> None:
        for view_name in (
            "reporting_revenue_breakdowns",
            "reporting_revenue_by_route",
            "reporting_revenue_by_seat_class",
        ):
            conn.execute(f"REFRESH MATERIALIZED VIEW CONCURRENTLY {view_name}")

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
            ORDER BY occurred_at DESC, event_id
            LIMIT 10000
            """,
            params,
        ).fetchall()
        rows.reverse()
        events = [_event_from_row(row) for row in rows]
        capture_ids = [event.event_id for event in events if event.event_type == "PaymentCaptured"]
        capture_refs: dict[str, tuple[str, str | None, str | None]] = {}
        if capture_ids:
            capture_refs = {
                str(row[0]): (
                    str(row[1]),
                    None if row[2] is None else str(row[2]),
                    None if row[3] is None else str(row[3]),
                )
                for row in conn.execute(
                    """
                    SELECT event_id,business_ref,payment_intent_id,payment_purpose
                    FROM reporting_capture_references
                    WHERE event_id = ANY(%s)
                    """,
                    (capture_ids,),
                ).fetchall()
            }
        events = [
            replace(
                event,
                business_ref=capture_refs[event.event_id][0],
                payment_intent_id=capture_refs[event.event_id][1],
                payment_purpose=capture_refs[event.event_id][2],
            )
            if event.event_id in capture_refs else event
            for event in events
        ]
        facts = self._load_revenue_facts(conn, events)
        aggregator = MetricAggregator(revenue_facts=facts)
        for event in events:
            aggregator.record(event)
        return aggregator

    @staticmethod
    def _load_revenue_facts(conn: Any, events: Sequence[OperationalEvent]) -> RevenueFactIndex:
        facts = RevenueFactIndex()
        business_refs = sorted({event.business_ref for event in events if event.business_ref})
        scopes: list[Sequence[Any]] = []
        if business_refs:
            scopes = conn.execute(
                """
                SELECT business_ref,order_id,segment_refs,traveler_refs,source_occurred_at
                FROM reporting_business_scopes
                WHERE business_ref = ANY(%s)
                """,
                (business_refs,),
            ).fetchall()
            for row in scopes:
                facts.record_business_order(str(row[0]), str(row[1]), row[2] or (), row[3] or (), _row_datetime(row[4]))
        order_ids = sorted(
            {ref for ref in business_refs if ref.startswith("ord-")}
            | {str(row[1]) for row in scopes}
        )
        if not order_ids:
            return facts
        for row in conn.execute(
            """
            SELECT order_id,attachment_type,source_occurred_at
            FROM reporting_order_attachments
            WHERE order_id = ANY(%s)
            """,
            (order_ids,),
        ).fetchall():
            facts.record_attachment(str(row[0]), str(row[1]), _row_datetime(row[2]))
        routes_by_order: dict[str, list[tuple[str, date | None]]] = {}
        travelers_by_order: dict[str, list[tuple[str, str]]] = {}
        occurred_by_order: dict[str, datetime] = {}
        for row in conn.execute(
            """
            SELECT order_id,route_id,service_date,source_occurred_at
            FROM reporting_order_routes
            WHERE order_id = ANY(%s)
            """,
            (order_ids,),
        ).fetchall():
            order_id = str(row[0])
            service_date = None if row[2] is None else _row_date(row[2], _row_datetime(row[3]))
            routes_by_order.setdefault(order_id, []).append((str(row[1]), service_date))
            occurred_by_order[order_id] = max(occurred_by_order.get(order_id, _row_datetime(row[3])), _row_datetime(row[3]))
        for row in conn.execute(
            """
            SELECT order_id,traveler_ref,traveler_type,source_occurred_at
            FROM reporting_order_travelers
            WHERE order_id = ANY(%s)
            """,
            (order_ids,),
        ).fetchall():
            order_id = str(row[0])
            travelers_by_order.setdefault(order_id, []).append((str(row[1]), str(row[2])))
            occurred_by_order[order_id] = max(occurred_by_order.get(order_id, _row_datetime(row[3])), _row_datetime(row[3]))
        for order_id in order_ids:
            occurred_at = occurred_by_order.get(order_id)
            if occurred_at is not None:
                facts.record_order(order_id, routes_by_order.get(order_id, ()), travelers_by_order.get(order_id, ()), occurred_at)
        for row in conn.execute(
            """
            SELECT order_id,segment_ref,traveler_ref,seat_class,source_occurred_at,source_priority
            FROM reporting_seat_classes
            WHERE order_id = ANY(%s)
            """,
            (order_ids,),
        ).fetchall():
            facts.record_seat_class(
                str(row[0]), str(row[1]), str(row[2]), str(row[3]), _row_datetime(row[4]), int(row[5])
            )
        return facts

    def _all_dashboards(self, conn: Any, *, for_update: bool = False) -> list[tuple[DashboardReadModel, int]]:
        # FOR UPDATE on the write path: every consumer rebuilds the same handful of
        # dashboard rows, so without the lock they read the same version, race to
        # write it, and all but one fail with OptimisticConcurrencyError -- which
        # redelivers the event and repeats the whole handler, the aggregator load
        # included. The expensive work is already done by the time this is called,
        # so the lock is held only for the rebuild and the outbox insert.
        lock = " ORDER BY id FOR UPDATE" if for_update else ""
        return [(_dashboard_from_json(row[2]), int(row[1])) for row in conn.execute(f"SELECT id, version, data FROM dashboard_read_model_snapshots{lock}").fetchall()]

    def _processed_count(self, conn: Any) -> int:
        return int(conn.execute("SELECT count(*) FROM processed_events").fetchone()[0])

    def _maybe_rebuild(self, conn: Any, dashboard: DashboardReadModel, version: int, envelope: EventEnvelope, event_count: int | None = None) -> None:
        if dashboard.status is not ReadModelStatus.STALE:
            return
        now = utc_now()
        if event_count is None:
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
        now = at or utc_now()
        with self._pool.connection() as conn:
            row = conn.execute(
                """
                WITH recent AS MATERIALIZED (
                  SELECT event_type,occurred_at,amount,currency,booking_latency_ms,flags
                  FROM reporting_metric_events
                  WHERE occurred_at >= %s - interval '1 hour' AND occurred_at <= %s
                ),
                global_metrics AS (
                  SELECT
                    count(*) FILTER (
                      WHERE occurred_at >= %s - interval '60 seconds'
                        AND event_type = ANY(%s)
                    )::numeric / 60 AS orders_per_second,
                    percentile_cont(0.50) WITHIN GROUP (ORDER BY booking_latency_ms)
                      FILTER (WHERE booking_latency_ms IS NOT NULL) AS latency_p50,
                    percentile_cont(0.95) WITHIN GROUP (ORDER BY booking_latency_ms)
                      FILTER (WHERE booking_latency_ms IS NOT NULL) AS latency_p95,
                    percentile_cont(0.99) WITHIN GROUP (ORDER BY booking_latency_ms)
                      FILTER (WHERE booking_latency_ms IS NOT NULL) AS latency_p99,
                    count(*) FILTER (WHERE event_type = ANY(%s))::numeric
                      / NULLIF(count(*) FILTER (WHERE event_type = ANY(%s) OR event_type = ANY(%s)),0) AS scalper_block_rate
                  FROM recent
                ),
                refund_window AS (
                  SELECT count(*) FILTER (WHERE event_type = ANY(%s))::numeric
                           / NULLIF(count(*) FILTER (WHERE event_type = ANY(%s)),0) AS refund_rate
                  FROM reporting_metric_events
                  WHERE event_type = ANY(%s)
                    AND occurred_at >= %s - interval '24 hours' AND occurred_at <= %s
                ),
                revenue AS (
                  SELECT COALESCE(currency,'USD') AS currency,SUM(amount) AS amount
                  FROM recent
                  WHERE event_type='PaymentCaptured' AND amount IS NOT NULL
                  GROUP BY COALESCE(currency,'USD')
                ),
                context AS (
                  SELECT COALESCE(flags->>'sourceContext','unknown') AS source_context,event_type,
                         count(*) AS event_count,max(occurred_at) AS last_occurred_at,
                         count(*) FILTER (WHERE COALESCE((flags->>'anomalySignal')::boolean,false)) AS anomaly_count
                  FROM recent
                  WHERE occurred_at >= %s - interval '5 minutes'
                  GROUP BY 1,2
                )
                SELECT global_metrics.orders_per_second,global_metrics.latency_p50,
                       global_metrics.latency_p95,global_metrics.latency_p99,
                       COALESCE(refund_window.refund_rate,0),COALESCE(global_metrics.scalper_block_rate,0),
                       COALESCE((SELECT jsonb_agg(jsonb_build_object('currency',currency,'amount',amount) ORDER BY currency) FROM revenue),'[]'::jsonb),
                       COALESCE((SELECT jsonb_agg(jsonb_build_object(
                         'sourceContext',source_context,'eventType',event_type,'count',event_count,
                         'lastOccurredAt',last_occurred_at,'anomalyCount',anomaly_count
                       ) ORDER BY source_context,event_type) FROM context),'[]'::jsonb)
                FROM global_metrics CROSS JOIN refund_window
                """,
                (
                    now,
                    now,
                    now,
                    list(_ORDER_CREATED_EVENT_TYPES),
                    list(_RISK_BLOCK_EVENT_TYPES),
                    list(_RISK_BLOCK_EVENT_TYPES),
                    list(_ORDER_CREATED_EVENT_TYPES),
                    list(_REFUND_EVENT_TYPES),
                    list(_ORDER_CREATED_EVENT_TYPES),
                    list(_REFUND_EVENT_TYPES + _ORDER_CREATED_EVENT_TYPES),
                    now,
                    now,
                    now,
                ),
            ).fetchone()
        revenue = tuple(Money(item["amount"], item["currency"]) for item in row[6])
        context_rollups = tuple(
            ContextEventRollup(
                str(item["sourceContext"]),
                str(item["eventType"]),
                int(item["count"]),
                _row_datetime(item["lastOccurredAt"]),
                int(item["anomalyCount"]),
            )
            for item in row[7]
        )
        return MetricSnapshot(
            generated_at=now,
            orders_per_second=round(float(row[0] or 0), 4),
            revenue_per_hour=revenue,
            fill_rate_by_route={},
            avg_booking_latency={
                "p50": float(row[1] or 0),
                "p95": float(row[2] or 0),
                "p99": float(row[3] or 0),
            },
            refund_rate=float(row[4]),
            scalper_block_rate=float(row[5]),
            context_rollups=context_rollups,
        )

    def _evaluate_anomalies(self, conn: Any, now: datetime) -> tuple[AnomalyDetected, ...]:
        detected: list[AnomalyDetected] = []
        revenue_rows = conn.execute(
            """
            SELECT COALESCE(currency,'USD'),
                   COALESCE(SUM(amount) FILTER (WHERE occurred_at >= %s - interval '1 hour' AND occurred_at <= %s),0),
                   COALESCE(SUM(amount) FILTER (WHERE occurred_at >= %s - interval '25 hours' AND occurred_at <= %s - interval '24 hours'),0)
            FROM reporting_metric_events
            WHERE event_type='PaymentCaptured' AND amount IS NOT NULL
              AND ((occurred_at >= %s - interval '1 hour' AND occurred_at <= %s)
                OR (occurred_at >= %s - interval '25 hours' AND occurred_at <= %s - interval '24 hours'))
            GROUP BY COALESCE(currency,'USD')
            """,
            (now, now, now, now, now, now, now, now),
        ).fetchall()
        revenue_ratios = [Decimal(str(row[1])) / Decimal(str(row[2])) for row in revenue_rows if Decimal(str(row[2])) > 0]
        if revenue_ratios:
            ratio = min(revenue_ratios)
            rule = self._detector._rule(AnomalyRuleId.REVENUE_DROP)
            if ratio < rule.threshold:
                detected.append(AnomalyDetected(rule.rule_id.value, ratio.quantize(Decimal("0.01")), rule.threshold, rule.severity, now, rule.urgent))

        row = conn.execute(
            """
            SELECT
              count(*) FILTER (WHERE event_type=ANY(%s) AND occurred_at >= %s - interval '1 minute') AS current_orders,
              count(*) FILTER (WHERE event_type=ANY(%s) AND occurred_at >= %s - interval '6 minutes' AND occurred_at < %s - interval '1 minute') AS prior_orders,
              count(*) FILTER (WHERE event_type=ANY(%s) AND occurred_at >= %s - interval '5 minutes') AS payment_failures,
              count(*) FILTER (WHERE (event_type=ANY(%s) OR event_type='PaymentCaptured') AND occurred_at >= %s - interval '5 minutes') AS payment_attempts,
              count(*) FILTER (WHERE event_type=ANY(%s) AND occurred_at >= %s - interval '1 hour') AS refunds,
              count(*) FILTER (WHERE event_type=ANY(%s) AND occurred_at >= %s - interval '1 hour') AS orders
            FROM reporting_metric_events
            WHERE event_type=ANY(%s) AND occurred_at >= %s - interval '1 hour'
            """,
            (
                list(_ORDER_CREATED_EVENT_TYPES), now,
                list(_ORDER_CREATED_EVENT_TYPES), now, now,
                list(_PAYMENT_FAILED_EVENT_TYPES), now,
                list(_PAYMENT_FAILED_EVENT_TYPES), now,
                list(_REFUND_EVENT_TYPES), now,
                list(_ORDER_CREATED_EVENT_TYPES), now,
                list(_ORDER_CREATED_EVENT_TYPES + _PAYMENT_FAILED_EVENT_TYPES + _PAYMENT_CAPTURED_EVENT_TYPES + _REFUND_EVENT_TYPES), now,
            ),
        ).fetchone()
        current_orders, prior_orders, failures, attempts, refunds, orders = (int(value or 0) for value in row)
        prior_average = Decimal(prior_orders) / Decimal("5")
        order_rule = self._detector._rule(AnomalyRuleId.ORDER_SPIKE)
        if prior_average > 0 and Decimal(current_orders) > prior_average * order_rule.threshold:
            detected.append(AnomalyDetected(order_rule.rule_id.value, (Decimal(current_orders) / prior_average).quantize(Decimal("0.01")), order_rule.threshold, order_rule.severity, now, order_rule.urgent))
        error_rule = self._detector._rule(AnomalyRuleId.ERROR_RATE_SPIKE)
        error_rate = Decimal(failures) / Decimal(attempts) if attempts else Decimal("0")
        if attempts and error_rate > error_rule.threshold:
            detected.append(AnomalyDetected(error_rule.rule_id.value, error_rate.quantize(Decimal("0.01")), error_rule.threshold, error_rule.severity, now, error_rule.urgent))
        refund_rule = self._detector._rule(AnomalyRuleId.REFUND_SURGE)
        refund_rate = Decimal(refunds) / Decimal(orders) if orders else Decimal("0")
        if orders and refund_rate > refund_rule.threshold:
            detected.append(AnomalyDetected(refund_rule.rule_id.value, refund_rate.quantize(Decimal("0.01")), refund_rule.threshold, refund_rule.severity, now, refund_rule.urgent))

        exhausted = int(conn.execute(
            """
            WITH latest AS (
              SELECT DISTINCT ON (route_id,service_date) route_id,service_date,capacity,confirmed
              FROM reporting_metric_events
              WHERE route_id IS NOT NULL AND service_date IS NOT NULL
                AND (capacity IS NOT NULL OR confirmed IS NOT NULL)
              ORDER BY route_id,service_date,occurred_at DESC,event_id DESC
            )
            SELECT count(*) FROM latest WHERE capacity>0 AND confirmed>=capacity
            """
        ).fetchone()[0])
        capacity_rule = self._detector._rule(AnomalyRuleId.CAPACITY_EXHAUSTION)
        if Decimal(exhausted) > capacity_rule.threshold:
            detected.append(AnomalyDetected(capacity_rule.rule_id.value, Decimal(exhausted), capacity_rule.threshold, capacity_rule.severity, now, capacity_rule.urgent))
        return tuple(detected)

    def route_metrics(self, *, limit: int = 20, offset: int = 0, at: datetime | None = None) -> tuple[tuple[RouteMetrics, ...], int]:
        now = at or utc_now()
        with self._pool.connection() as conn:
            rows = conn.execute(
                """
                WITH recent AS MATERIALIZED (
                  SELECT event_id,event_type,occurred_at,route_id,
                         COALESCE(service_date,(occurred_at AT TIME ZONE 'UTC')::date) AS service_date,
                         seat_class,capacity,confirmed
                  FROM reporting_metric_events
                  WHERE occurred_at >= %s - interval '24 hours' AND occurred_at <= %s
                    AND route_id IS NOT NULL
                ),
                route_groups AS MATERIALIZED (
                  SELECT route_id,service_date,
                         count(*) FILTER (WHERE event_type=ANY(%s)) AS searches,
                         count(*) FILTER (WHERE event_type=ANY(%s)) AS bookings,
                         (array_agg(confirmed ORDER BY occurred_at DESC,event_id DESC)
                           FILTER (WHERE confirmed IS NOT NULL))[1] AS latest_confirmed,
                         (array_agg(capacity ORDER BY occurred_at DESC,event_id DESC)
                           FILTER (WHERE capacity IS NOT NULL))[1] AS latest_capacity
                  FROM recent GROUP BY route_id,service_date
                ),
                summary AS (SELECT count(*) AS total_items FROM route_groups),
                page AS MATERIALIZED (
                  SELECT * FROM route_groups
                  ORDER BY service_date DESC,route_id
                  LIMIT %s OFFSET %s
                ),
                class_groups AS (
                  SELECT recent.route_id,recent.service_date,recent.seat_class,
                         count(*) FILTER (WHERE recent.event_type=ANY(%s)) AS bookings,
                         (array_agg(recent.confirmed ORDER BY recent.occurred_at DESC,recent.event_id DESC)
                           FILTER (WHERE recent.confirmed IS NOT NULL))[1] AS latest_confirmed,
                         (array_agg(recent.capacity ORDER BY recent.occurred_at DESC,recent.event_id DESC)
                           FILTER (WHERE recent.capacity IS NOT NULL))[1] AS latest_capacity
                  FROM recent JOIN page USING(route_id,service_date)
                  WHERE recent.seat_class IS NOT NULL
                  GROUP BY recent.route_id,recent.service_date,recent.seat_class
                ),
                class_rates AS (
                  SELECT route_id,service_date,jsonb_object_agg(
                    seat_class,
                    CASE WHEN COALESCE(latest_capacity,0)=0 THEN 0
                         ELSE LEAST(1.0,COALESCE(latest_confirmed,bookings)::numeric/latest_capacity) END
                    ORDER BY seat_class
                  ) AS utilization
                  FROM class_groups GROUP BY route_id,service_date
                )
                SELECT page.route_id,page.service_date,page.searches,page.bookings,
                       COALESCE(page.latest_confirmed,page.bookings),COALESCE(page.latest_capacity,0),
                       COALESCE(class_rates.utilization,'{}'::jsonb),summary.total_items
                FROM summary
                LEFT JOIN page ON true
                LEFT JOIN class_rates USING(route_id,service_date)
                ORDER BY page.service_date DESC,page.route_id
                """,
                (
                    now,
                    now,
                    list(_SEARCH_EVENT_TYPES),
                    list(_ORDER_CREATED_EVENT_TYPES),
                    limit,
                    offset,
                    list(_ORDER_CREATED_EVENT_TYPES),
                ),
            ).fetchall()
        total = int(rows[0][7])
        metrics = tuple(
            RouteMetrics(
                route_id=str(row[0]),
                service_date=_row_date(row[1], now) or now.date(),
                route_demand=int(row[2]) + int(row[3]),
                bookings=int(row[3]),
                searches=int(row[2]),
                confirmed=int(row[4]),
                capacity=int(row[5]),
                seat_utilization_by_class={str(key): float(value) for key, value in dict(row[6]).items()},
            )
            for row in rows
            if row[0] is not None
        )
        return metrics, total

    def context_count_report(self, group_by: str = "source_context", limit: int = 20, offset: int = 0, at: datetime | None = None) -> ContextCountReport:
        with self._pool.connection() as conn:
            return self._load_aggregator(conn).context_count_report(group_by=group_by, limit=limit, offset=offset, at=at)

    def revenue_report(self, group_by: str = "route", limit: int = 20, offset: int = 0, at: datetime | None = None) -> RevenueReport:
        if group_by not in {"route", "seat_class", "channel", "traveler_type", "time_period"}:
            with self._pool.connection() as conn:
                return self._load_aggregator(conn).revenue_report(group_by=group_by, limit=limit, offset=offset, at=at)
        with self._pool.connection() as conn:
            rows = conn.execute(
                """
                WITH filtered AS MATERIALIZED (
                  SELECT dimension_value, currency, revenue, payment_count, distance_km,
                         ancillary_count, insurance_count
                  FROM reporting_revenue_breakdowns
                  WHERE dimension = %s
                ),
                currency_totals AS (
                  SELECT currency, SUM(revenue) AS revenue
                  FROM filtered
                  GROUP BY currency
                ),
                summary AS (
                  SELECT
                    (SELECT COUNT(*) FROM filtered) AS total_items,
                    COALESCE(array_agg(currency ORDER BY currency), ARRAY[]::text[]) AS currencies,
                    COALESCE(array_agg(revenue ORDER BY currency), ARRAY[]::numeric[]) AS revenues
                  FROM currency_totals
                ),
                page AS (
                  SELECT *
                  FROM filtered
                  ORDER BY currency, revenue DESC, dimension_value
                  LIMIT %s OFFSET %s
                )
                SELECT page.dimension_value, page.currency, page.revenue, page.payment_count,
                       page.distance_km, page.ancillary_count, page.insurance_count,
                       summary.total_items, summary.currencies, summary.revenues
                FROM summary
                LEFT JOIN page ON true
                ORDER BY page.currency, page.revenue DESC, page.dimension_value
                """,
                (group_by, limit, offset),
            ).fetchall()
        summary = rows[0]
        items = tuple(self._revenue_item_from_view(group_by, row) for row in rows if row[0] is not None)
        totals = tuple(
            Money(amount, currency)
            for currency, amount in zip(summary[8], summary[9], strict=True)
        )
        return RevenueReport(at or utc_now(), group_by, items, totals, int(summary[7]), limit, offset)

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
                {
                    "metric": "revenue_per_hour",
                    "points": [{
                        "at": rfc3339_utc(snapshot.generated_at),
                        "values": [
                            {"amount": str(total.amount), "currency": total.currency}
                            for total in snapshot.revenue_per_hour
                        ],
                    }],
                },
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
