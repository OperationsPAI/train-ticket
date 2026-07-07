from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Any, Mapping

from train_ticket_platform.storage import OutboxAppender, SnapshotRepository
from trip_planning.domain import AvailabilityHint, Itinerary, LegCandidate, PriceHint
from trip_planning.events import EventEnvelope



def _jsonb_payload(value: Mapping[str, Any]) -> Any:
    try:
        from psycopg.types.json import Jsonb

        return Jsonb(dict(value))
    except ImportError:  # pragma: no cover - psycopg optional in unit tests
        return json.dumps(dict(value), separators=(",", ":"))

def _json_obj(data: Mapping[str, Any] | str) -> Mapping[str, Any]:
    return json.loads(data) if isinstance(data, str) else data


def _dt(value: datetime | str) -> datetime:
    if isinstance(value, datetime):
        return value.astimezone(UTC) if value.tzinfo else value.replace(tzinfo=UTC)
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


class TransactionalOutboxPublisher:
    def __init__(self, pool: Any, outbox: OutboxAppender | None = None) -> None:
        self._pool = pool
        self._outbox = outbox or OutboxAppender()

    def publish(self, envelope: EventEnvelope) -> None:
        with self._pool.connection() as conn:
            with conn.transaction():
                self._outbox.append(conn, envelope)


class PostgresPlanStore:
    def __init__(self, pool: Any) -> None:
        self._pool = pool
        self._itineraries = SnapshotRepository("itinerary_snapshots")

    def clear(self) -> None:
        with self._pool.connection() as conn:
            with conn.transaction():
                for table in ("plan_index_events", "plan_services", "plan_segments", "plan_nodes", "itinerary_snapshots"):
                    conn.execute(f"DELETE FROM {table}")

    def apply_envelope(self, envelope: Any) -> bool:
        event_id = str(getattr(envelope, "eventId", ""))
        event_type = str(getattr(envelope, "eventType", ""))
        payload = dict(getattr(envelope, "payload", {}) or {})
        producer = str(getattr(envelope, "producer", ""))
        occurred_at = getattr(envelope, "occurredAt", "")
        with self._pool.connection() as conn:
            with conn.transaction():
                if event_id:
                    row = conn.execute("INSERT INTO processed_events(event_id, stream) VALUES (%s, %s) ON CONFLICT DO NOTHING RETURNING event_id", (event_id, f"events:{producer}")).fetchone()
                    if row is None:
                        return False
                    conn.execute("INSERT INTO plan_index_events(event_id, event_type, producer, occurred_at, payload) VALUES (%s, %s, %s, %s, %s) ON CONFLICT DO NOTHING", (event_id, event_type, producer, str(occurred_at), _jsonb_payload(payload)))
                self._apply_locked(conn, event_type, payload)
        return True

    def apply(self, event_type: str, payload: Mapping[str, object]) -> None:
        with self._pool.connection() as conn:
            with conn.transaction():
                self._apply_locked(conn, event_type, dict(payload))

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

    def candidates(self, origin_ref: str, destination_ref: str, departure_date: str) -> list[Itinerary]:
        with self._pool.connection() as conn:
            rows = conn.execute(
                """
                WITH requested_origin AS (SELECT place_id FROM plan_nodes WHERE node_id = %s),
                     requested_destination AS (SELECT place_id FROM plan_nodes WHERE node_id = %s),
                     origin_refs AS (SELECT %s AS ref UNION SELECT node_id FROM plan_nodes WHERE place_id = %s UNION SELECT node_id FROM plan_nodes WHERE place_id = (SELECT place_id FROM requested_origin)),
                     destination_refs AS (SELECT %s AS ref UNION SELECT node_id FROM plan_nodes WHERE place_id = %s UNION SELECT node_id FROM plan_nodes WHERE place_id = (SELECT place_id FROM requested_destination))
                SELECT segment_ref, scheduled_service_ref, origin_stop_ref, destination_stop_ref, departure_time, arrival_time
                  FROM plan_segments
                 WHERE departure_date = %s::date
                   AND origin_stop_ref IN (SELECT ref FROM origin_refs WHERE ref IS NOT NULL)
                   AND destination_stop_ref IN (SELECT ref FROM destination_refs WHERE ref IS NOT NULL)
                 ORDER BY departure_time, segment_ref
                """,
                (origin_ref, destination_ref, origin_ref, origin_ref, destination_ref, destination_ref, departure_date),
            ).fetchall()
        return [self._itinerary_from_row(row) for row in rows]

    def _itinerary_from_row(self, row: Any) -> Itinerary:
        seg_ref, service_ref, origin, destination, departure_time, arrival_time = row
        departure = departure_time.astimezone(UTC)
        arrival = arrival_time.astimezone(UTC)
        return Itinerary(
            legs=(LegCandidate(service_plan_ref=str(service_ref or ""), service_segment_ref=str(seg_ref), origin_stop_ref=str(origin), destination_stop_ref=str(destination), departure_time=departure, arrival_time=arrival, mode="train", stop_refs=(str(origin), str(destination)), segment_refs=(str(seg_ref),)),),
            price_hint=PriceHint(amount_minor=0, currency="CNY", snapshot_ref=f"fare-snapshot:{seg_ref}", captured_at=departure, confidence=50),
            availability_hint=AvailabilityHint(status="UNKNOWN", snapshot_ref=f"availability-snapshot:{seg_ref}", captured_at=departure, confidence=50),
            planning_snapshot_refs=(f"planning-snapshot:{seg_ref}",),
        )

    def save_itinerary(self, itinerary: Mapping[str, object]) -> None:
        ref = str(itinerary["itineraryRef"])
        with self._pool.connection() as conn:
            with conn.transaction():
                snap = self._itineraries.get(conn, ref)
                self._itineraries.save(conn, ref, dict(itinerary), None if snap is None else int(snap[0]))

    def get_itinerary(self, itinerary_ref: str) -> dict[str, object] | None:
        with self._pool.connection() as conn:
            snap = self._itineraries.get(conn, itinerary_ref)
        return None if snap is None else dict(snap[1])
