from __future__ import annotations

import json
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import UTC, datetime
from typing import Any, Mapping

from train_ticket_platform.storage import OptimisticConcurrencyError, OutboxAppender, SnapshotRepository
from trip_planning.domain import Itinerary, MinimumConnectionTimeRule
from trip_planning.events import EventEnvelope
from trip_planning.read_model import SegmentRecord, build_itineraries


_CLEAR_TABLE_SQL = (
    "DELETE FROM plan_index_events",
    "DELETE FROM plan_services",
    "DELETE FROM plan_segments",
    "DELETE FROM plan_nodes",
    "DELETE FROM plan_mct_rules",
    "DELETE FROM itinerary_snapshots",
)


class _TxState:
    def __init__(self, connection: Any) -> None:
        self.connection = connection


_TX: ContextVar[_TxState | None] = ContextVar("trip_planning_postgres_tx", default=None)


def _jsonb_payload(value: Mapping[str, Any]) -> Any:
    try:
        from psycopg.types.json import Jsonb

        return Jsonb(dict(value))
    except ImportError:  # pragma: no cover - psycopg optional in unit tests
        return json.dumps(dict(value), separators=(",", ":"))


def _dt(value: datetime | str) -> datetime:
    if isinstance(value, datetime):
        return value.astimezone(UTC) if value.tzinfo else value.replace(tzinfo=UTC)
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


class TransactionalOutboxPublisher:
    def __init__(self, store: "PostgresPlanStore", outbox: OutboxAppender | None = None) -> None:
        self._store = store
        self._outbox = outbox or OutboxAppender()

    def publish(self, envelope: EventEnvelope) -> None:
        self._store.with_connection(lambda conn: self._outbox.append(conn, envelope))


class PostgresPlanStore:
    def __init__(self, pool: Any) -> None:
        self._pool = pool
        self._itineraries = SnapshotRepository("itinerary_snapshots")

    @contextmanager
    def transaction(self):
        state = _TX.get()
        if state is not None:
            yield state.connection
            return
        with self._pool.connection() as conn:
            with conn.transaction():
                token = _TX.set(_TxState(conn))
                try:
                    yield conn
                finally:
                    _TX.reset(token)

    def with_connection(self, fn: Any) -> Any:
        state = _TX.get()
        if state is not None:
            return fn(state.connection)
        with self.transaction() as conn:
            return fn(conn)

    def clear(self) -> None:
        def delete_all(conn: Any) -> None:
            for statement in _CLEAR_TABLE_SQL:
                conn.execute(statement)

        self.with_connection(delete_all)

    def apply_envelope(self, envelope: Any) -> bool:
        event_id = str(getattr(envelope, "eventId", ""))
        event_type = str(getattr(envelope, "eventType", ""))
        payload = dict(getattr(envelope, "payload", {}) or {})
        producer = str(getattr(envelope, "producer", ""))
        occurred_at = getattr(envelope, "occurredAt", "")

        def write(conn: Any) -> bool:
            if event_id:
                row = conn.execute("INSERT INTO processed_events(event_id, stream) VALUES (%s, %s) ON CONFLICT DO NOTHING RETURNING event_id", (event_id, f"events:{producer}")).fetchone()
                if row is None:
                    return False
                conn.execute("INSERT INTO plan_index_events(event_id, event_type, producer, occurred_at, payload) VALUES (%s, %s, %s, %s, %s) ON CONFLICT DO NOTHING", (event_id, event_type, producer, str(occurred_at), _jsonb_payload(payload)))
            self._apply_locked(conn, event_type, payload)
            return True

        return bool(self.with_connection(write))

    def apply(self, event_type: str, payload: Mapping[str, object]) -> None:
        self.with_connection(lambda conn: self._apply_locked(conn, event_type, dict(payload)))

    def load(self) -> None:
        # State is already durable in read-model tables; this method preserves the
        # in-memory PlanStore lifecycle contract for tests and startup code.
        return None

    def _apply_locked(self, conn: Any, event_type: str, payload: Mapping[str, object]) -> None:
        if event_type in ("ServicePlanPublished", "ScheduledServiceCreated"):
            ref = str(payload.get("scheduledServiceRef", ""))
            if ref:
                conn.execute("INSERT INTO plan_services(scheduled_service_ref, version, data) VALUES (%s, 1, %s) ON CONFLICT (scheduled_service_ref) DO UPDATE SET version = plan_services.version + 1, data = EXCLUDED.data, updated_at = now()", (ref, _jsonb_payload(dict(payload))))
        elif event_type in ("ServicePlanChanged", "ServiceSegmentCreated"):
            seg = str(payload.get("segmentRef", ""))
            origin = str(payload.get("originStopRef", ""))
            destination = str(payload.get("destinationStopRef", ""))
            departure_raw = str(payload.get("departureTime", ""))
            if seg and origin and destination and departure_raw:
                departure = _dt(departure_raw)
                arrival = _dt(str(payload.get("arrivalTime", departure_raw)))
                conn.execute("INSERT INTO plan_segments(segment_ref, version, scheduled_service_ref, origin_stop_ref, destination_stop_ref, departure_time, departure_date, arrival_time, data) VALUES (%s, 1, %s, %s, %s, %s, %s, %s, %s) ON CONFLICT (segment_ref) DO UPDATE SET version = plan_segments.version + 1, scheduled_service_ref = EXCLUDED.scheduled_service_ref, origin_stop_ref = EXCLUDED.origin_stop_ref, destination_stop_ref = EXCLUDED.destination_stop_ref, departure_time = EXCLUDED.departure_time, departure_date = EXCLUDED.departure_date, arrival_time = EXCLUDED.arrival_time, data = EXCLUDED.data, updated_at = now()", (seg, str(payload.get("scheduledServiceRef", "")), origin, destination, departure, departure.date(), arrival, _jsonb_payload(dict(payload))))
        elif event_type in ("TransportNodeRegistered", "TransportNodeAdded", "TransportNodeUpdated"):
            node = str(payload.get("nodeId", ""))
            place = str(payload.get("placeId", ""))
            if node and place:
                conn.execute("INSERT INTO plan_nodes(node_id, version, place_id, data) VALUES (%s, 1, %s, %s) ON CONFLICT (node_id) DO UPDATE SET version = plan_nodes.version + 1, place_id = EXCLUDED.place_id, data = EXCLUDED.data, updated_at = now()", (node, place, _jsonb_payload(dict(payload))))
        elif event_type == "MctRulePublished":
            rule = MinimumConnectionTimeRule.from_transfer_event(payload)
            conn.execute(
                "INSERT INTO plan_mct_rules(mct_rule_id, version, status, from_node_type, to_node_type, transfer_category, minimum_minutes, valid_from, valid_until, data) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s) ON CONFLICT (mct_rule_id, version) DO UPDATE SET status = EXCLUDED.status, from_node_type = EXCLUDED.from_node_type, to_node_type = EXCLUDED.to_node_type, transfer_category = EXCLUDED.transfer_category, minimum_minutes = EXCLUDED.minimum_minutes, valid_from = EXCLUDED.valid_from, valid_until = EXCLUDED.valid_until, data = EXCLUDED.data, updated_at = now()",
                (rule.mct_rule_id, rule.version, rule.status, rule.from_node_type, rule.to_node_type, rule.transfer_category, rule.minimum_minutes, rule.valid_from, rule.valid_until, _jsonb_payload(dict(payload))),
            )
        elif event_type == "MctRuleRetired":
            rule_id = str(payload.get("mctRuleId", ""))
            version = int(payload.get("version", 0))
            if rule_id and version:
                conn.execute("DELETE FROM plan_mct_rules WHERE mct_rule_id = %s AND version = %s", (rule_id, version))

    def candidates(self, origin_ref: str, destination_ref: str, departure_date: str) -> list[Itinerary]:
        with self._pool.connection() as conn:
            segment_rows = conn.execute(
                """
                SELECT segment_ref, scheduled_service_ref, origin_stop_ref, destination_stop_ref, departure_time, arrival_time
                  FROM plan_segments
                 WHERE departure_date = %s::date
                 ORDER BY departure_time, segment_ref
                """,
                (departure_date,),
            ).fetchall()
            node_rows = conn.execute("SELECT node_id, place_id, data FROM plan_nodes").fetchall()
            rule_rows = conn.execute("SELECT data FROM plan_mct_rules WHERE status = 'PUBLISHED'").fetchall()
        segments = tuple(
            SegmentRecord(
                segment_ref=str(row[0]),
                scheduled_service_ref=str(row[1] or ""),
                origin_stop_ref=str(row[2]),
                destination_stop_ref=str(row[3]),
                departure_time=row[4].astimezone(UTC),
                arrival_time=row[5].astimezone(UTC),
            )
            for row in segment_rows
        )
        node_place = {str(row[0]): str(row[1]) for row in node_rows}
        node_payloads = {str(row[0]): dict(row[2] or {}) for row in node_rows}
        mct_rules = tuple(MinimumConnectionTimeRule.from_transfer_event(dict(row[0] or {})) for row in rule_rows)
        return build_itineraries(
            origin_ref=origin_ref,
            destination_ref=destination_ref,
            departure_date=departure_date,
            segments=segments,
            node_place=node_place,
            node_payloads=node_payloads,
            mct_rules=mct_rules,
        )

    def save_itinerary(self, itinerary: Mapping[str, object]) -> None:
        ref = str(itinerary["itineraryRef"])
        payload = dict(itinerary)

        def write(conn: Any) -> None:
            for _attempt in range(3):
                snap = self._itineraries.get(conn, ref)
                if snap is not None and dict(snap[1]) == payload:
                    return
                try:
                    self._itineraries.save(conn, ref, payload, None if snap is None else int(snap[0]))
                    return
                except OptimisticConcurrencyError:
                    # Search writes are durable read-model snapshots keyed by a
                    # deterministic itinerary id. Concurrent searches can legitimately
                    # race to persist the same candidate; retry with the winning version
                    # and fall back to the already-readable snapshot below.
                    continue
            if self._itineraries.get(conn, ref) is None:
                raise OptimisticConcurrencyError(f"concurrent update detected for {ref}")

        self.with_connection(write)

    def get_itinerary(self, itinerary_ref: str) -> dict[str, object] | None:
        snap = self.with_connection(lambda conn: self._itineraries.get(conn, itinerary_ref))
        return None if snap is None else dict(snap[1])
