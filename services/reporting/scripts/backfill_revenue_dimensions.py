from __future__ import annotations

import argparse
import json
import os
import time
from collections.abc import Iterator, Mapping, Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import psycopg
from psycopg import sql
from psycopg.conninfo import conninfo_to_dict, make_conninfo
from psycopg.types.json import Jsonb


SETUP_VERSION = "007_authoritative_revenue_facts.sql"
BACKFILL_NAME = "authoritative-revenue-v1"
ROOT = Path(__file__).resolve().parents[1]


def _database_url(base_url: str, database: str) -> str:
    values = conninfo_to_dict(base_url)
    values["dbname"] = database
    return make_conninfo(**values)


def _source_urls(reporting_url: str, env: Mapping[str, str]) -> dict[str, str]:
    names = {
        "payment": "PAYMENT_DATABASE_URL",
        "journey_order": "JOURNEY_ORDER_DATABASE_URL",
        "post_sales": "POST_SALES_DATABASE_URL",
        "booking_orchestration": "BOOKING_ORCHESTRATION_DATABASE_URL",
        "seat_assignment": "SEAT_ASSIGNMENT_DATABASE_URL",
        "ancillary_service": "ANCILLARY_SERVICE_DATABASE_URL",
        "travel_insurance": "TRAVEL_INSURANCE_DATABASE_URL",
    }
    return {
        name: env.get(variable, "").strip() or _database_url(reporting_url, name)
        for name, variable in names.items()
    }


def _json(value: Any) -> dict[str, Any]:
    if isinstance(value, str):
        value = json.loads(value)
    return dict(value)


def _text(value: Any) -> str:
    return "" if value is None else str(value).strip()


def _strings(value: Any) -> list[str]:
    if not isinstance(value, (list, tuple)):
        return []
    return sorted({item for item in (_text(item) for item in value) if item})


def _timestamp(value: Any, fallback: datetime) -> datetime:
    if isinstance(value, datetime):
        return (value if value.tzinfo else value.replace(tzinfo=UTC)).astimezone(UTC)
    if value:
        try:
            return datetime.fromisoformat(str(value).replace("Z", "+00:00")).astimezone(UTC)
        except ValueError:
            pass
    return fallback.astimezone(UTC) if fallback.tzinfo else fallback.replace(tzinfo=UTC)


def capture_event_row(
    event: Any,
    updated_at: datetime,
    snapshot_business_ref: Any = None,
    snapshot_payment_intent_id: Any = None,
    snapshot_purpose: Any = None,
) -> tuple[Any, ...] | None:
    data = _json(event)
    payload = data.get("payload") if isinstance(data.get("payload"), Mapping) else {}
    event_id = _text(data.get("eventId"))
    business_ref = _text(payload.get("businessRef") or payload.get("orderId") or snapshot_business_ref)
    if data.get("eventType") != "PaymentCaptured" or not event_id:
        return None
    business_ref = business_ref or f"unresolved:{event_id}"
    return (
        event_id,
        business_ref,
        _text(payload.get("paymentIntentId") or payload.get("intentId") or snapshot_payment_intent_id) or None,
        _text(payload.get("purpose") or snapshot_purpose) or None,
        _timestamp(data.get("occurredAt"), updated_at),
    )


def capture_rows(snapshot: Any, updated_at: datetime) -> list[tuple[Any, ...]]:
    data = _json(snapshot)
    return [
        row
        for event in data.get("domainEvents", ())
        if (row := capture_event_row(
            event,
            updated_at,
            data.get("businessRef"),
            data.get("paymentIntentId"),
            data.get("purpose"),
        ))
    ]


def order_rows(snapshot: Any, updated_at: datetime) -> tuple[list[tuple[Any, ...]], list[tuple[Any, ...]], tuple[Any, ...] | None]:
    data = _json(snapshot)
    order_id = _text(data.get("orderId"))
    if not order_id:
        return [], [], None
    routes = []
    for segment in data.get("segments", ()):
        if not isinstance(segment, Mapping):
            continue
        route_id = _text(segment.get("segmentRef"))
        departure = _text(segment.get("departureTime"))
        if route_id:
            routes.append((order_id, route_id, departure[:10] or None, updated_at))
    travelers = []
    for traveler in data.get("travelers", data.get("travelerRefs", ())):
        if not isinstance(traveler, Mapping):
            continue
        traveler_ref = _text(traveler.get("travelerId"))
        traveler_type = _text(traveler.get("travelerType"))
        if traveler_ref and traveler_type:
            travelers.append((order_id, traveler_ref, traveler_type, updated_at))
    return routes, travelers, (order_id, order_id, [], [], updated_at)


def post_sales_scope_row(snapshot: Any, updated_at: datetime) -> tuple[Any, ...] | None:
    data = _json(snapshot)
    case_id = _text(data.get("caseId"))
    if case_id and not case_id.startswith("psc-"):
        case_id = "psc-" + case_id
    order_id = _text(data.get("journeyOrderId") or data.get("orderId"))
    if not case_id or not order_id:
        return None
    scope = data.get("scope") if isinstance(data.get("scope"), Mapping) else {}
    return case_id, order_id, _strings(scope.get("segmentRefs")), _strings(scope.get("travelerRefs")), updated_at


def ancillary_rows(snapshot: Any, updated_at: datetime) -> tuple[tuple[Any, ...] | None, tuple[Any, ...] | None]:
    data = _json(snapshot)
    item_id = _text(data.get("ancillaryOrderItemId"))
    order_id = _text(data.get("journeyOrderId") or data.get("orderId"))
    if not item_id or not order_id:
        return None, None
    segment = _text(data.get("segmentRef"))
    traveler = _text(data.get("travelerRef"))
    catalog = data.get("catalogSnapshot") if isinstance(data.get("catalogSnapshot"), Mapping) else {}
    service_type = _text(data.get("serviceType") or catalog.get("serviceType")).upper()
    attachment_type = "INSURANCE" if service_type == "INSURANCE" else "ANCILLARY"
    purchased = bool(_text(data.get("paymentIntentId") or data.get("paymentCapturedAt"))) or _text(data.get("status")) in {
        "CONFIRMED", "FULFILLMENT_READY", "FULFILLED", "REFUND_PENDING", "REFUNDED"
    }
    return (
        item_id, order_id, [segment] if segment else [], [traveler] if traveler else [], updated_at
    ), ((order_id, attachment_type, updated_at) if purchased else None)


def insurance_rows(snapshot: Any, updated_at: datetime) -> tuple[list[tuple[Any, ...]], list[tuple[Any, ...]]]:
    data = _json(snapshot)
    order_id = _text(data.get("journeyOrderId") or data.get("orderId"))
    if not order_id:
        return [], []
    segments = _strings(data.get("segmentRefs"))
    traveler = _text(data.get("travelerRef"))
    travelers = [traveler] if traveler else []
    scopes, attachments = [], []
    for ref in (_text(data.get("policyId")), _text(data.get("ancillaryOrderItemId"))):
        if ref:
            scopes.append((ref, order_id, segments, travelers, updated_at))
            attachments.append((order_id, "INSURANCE", updated_at))
    return scopes, attachments


def seat_row(snapshot: Any, updated_at: datetime, priority: int) -> tuple[Any, ...] | None:
    data = _json(snapshot)
    values = (
        _text(data.get("journeyOrderId") or data.get("orderId")),
        _text(data.get("segmentRef")),
        _text(data.get("travelerRef")),
        _text(data.get("requestedClassRef") or data.get("classRef")),
    )
    return None if not all(values) else (*values, priority, updated_at)


class BackfillWriter:
    def __init__(self, conn: psycopg.Connection[Any], delay_seconds: float = 0.0) -> None:
        self.conn = conn
        self.delay_seconds = delay_seconds
        self.counts: dict[str, int] = {}

    def write(self, key: str, statement: str, rows: Sequence[tuple[Any, ...]]) -> None:
        if not rows:
            return
        with self.conn.cursor() as cursor:
            cursor.executemany(statement, rows)
        self.conn.commit()
        self.counts[key] = self.counts.get(key, 0) + len(rows)
        if self.delay_seconds:
            time.sleep(self.delay_seconds)


CAPTURE_SQL = """
INSERT INTO reporting_capture_references(event_id,business_ref,payment_intent_id,payment_purpose,source_occurred_at)
VALUES (%s,%s,%s,%s,%s) ON CONFLICT(event_id) DO UPDATE SET
 business_ref=EXCLUDED.business_ref,payment_intent_id=EXCLUDED.payment_intent_id,
 payment_purpose=EXCLUDED.payment_purpose,source_occurred_at=EXCLUDED.source_occurred_at
WHERE reporting_capture_references.source_occurred_at<=EXCLUDED.source_occurred_at
"""
ROUTE_SQL = """
INSERT INTO reporting_order_routes(order_id,route_id,service_date,source_occurred_at)
VALUES (%s,%s,%s,%s) ON CONFLICT(order_id,route_id) DO UPDATE SET
 service_date=EXCLUDED.service_date,source_occurred_at=EXCLUDED.source_occurred_at
WHERE reporting_order_routes.source_occurred_at<=EXCLUDED.source_occurred_at
"""
TRAVELER_SQL = """
INSERT INTO reporting_order_travelers(order_id,traveler_ref,traveler_type,source_occurred_at)
VALUES (%s,%s,%s,%s) ON CONFLICT(order_id,traveler_ref) DO UPDATE SET
 traveler_type=EXCLUDED.traveler_type,source_occurred_at=EXCLUDED.source_occurred_at
WHERE reporting_order_travelers.source_occurred_at<=EXCLUDED.source_occurred_at
"""
SCOPE_SQL = """
INSERT INTO reporting_business_scopes(business_ref,order_id,segment_refs,traveler_refs,source_occurred_at)
VALUES (%s,%s,%s,%s,%s) ON CONFLICT(business_ref) DO UPDATE SET
 order_id=EXCLUDED.order_id,
 segment_refs=CASE WHEN cardinality(EXCLUDED.segment_refs)>0 THEN EXCLUDED.segment_refs ELSE reporting_business_scopes.segment_refs END,
 traveler_refs=CASE WHEN cardinality(EXCLUDED.traveler_refs)>0 THEN EXCLUDED.traveler_refs ELSE reporting_business_scopes.traveler_refs END,
 source_occurred_at=EXCLUDED.source_occurred_at
WHERE reporting_business_scopes.source_occurred_at<=EXCLUDED.source_occurred_at
"""
SEAT_SQL = """
INSERT INTO reporting_seat_classes(order_id,segment_ref,traveler_ref,seat_class,source_priority,source_occurred_at)
VALUES (%s,%s,%s,%s,%s,%s) ON CONFLICT(order_id,segment_ref,traveler_ref) DO UPDATE SET
 seat_class=EXCLUDED.seat_class,source_priority=EXCLUDED.source_priority,source_occurred_at=EXCLUDED.source_occurred_at
WHERE (reporting_seat_classes.source_priority,reporting_seat_classes.source_occurred_at)
 <=(EXCLUDED.source_priority,EXCLUDED.source_occurred_at)
"""
ATTACHMENT_SQL = """
INSERT INTO reporting_order_attachments(order_id,attachment_type,source_occurred_at)
VALUES (%s,%s,%s) ON CONFLICT(order_id,attachment_type) DO UPDATE SET
 source_occurred_at=EXCLUDED.source_occurred_at
WHERE reporting_order_attachments.source_occurred_at<=EXCLUDED.source_occurred_at
"""


def _iter_snapshots(url: str, table: str, batch_size: int, since: datetime | None = None) -> Iterator[list[tuple[Any, datetime]]]:
    last = ""
    with psycopg.connect(url) as conn:
        while True:
            with conn.transaction():
                conn.execute("SET TRANSACTION READ ONLY")
                where = "id>%s" if since is None else "id>%s AND updated_at>=%s"
                params: tuple[Any, ...] = (last, batch_size) if since is None else (last, since, batch_size)
                rows = conn.execute(
                    sql.SQL(f"SELECT id,data,updated_at FROM {{}} WHERE {where} ORDER BY id LIMIT %s").format(sql.Identifier(table)),
                    params,
                ).fetchall()
            if not rows:
                return
            yield [(row[1], row[2]) for row in rows]
            last = str(rows[-1][0])


def _ensure_setup(conn: psycopg.Connection[Any], schema: str) -> None:
    conn.execute(sql.SQL("SET search_path TO {}").format(sql.Identifier(schema)))
    conn.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version text PRIMARY KEY,applied_at timestamptz NOT NULL DEFAULT now())")
    if conn.execute("SELECT 1 FROM schema_migrations WHERE version=%s", (SETUP_VERSION,)).fetchone() is None:
        conn.execute((ROOT / "migrations" / SETUP_VERSION).read_text())
        conn.execute("INSERT INTO schema_migrations(version) VALUES (%s)", (SETUP_VERSION,))
    conn.commit()


def _required_refs(conn: psycopg.Connection[Any], prefix: str, batch_size: int) -> Iterator[list[str]]:
    last = ""
    while True:
        rows = conn.execute(
            """
            SELECT DISTINCT business_ref
            FROM reporting_capture_references
            WHERE business_ref LIKE %s AND business_ref>%s
            ORDER BY business_ref LIMIT %s
            """,
            (prefix + "%", last, batch_size),
        ).fetchall()
        if not rows:
            return
        values = [str(row[0]) for row in rows]
        yield values
        last = values[-1]


def _required_order_ids(conn: psycopg.Connection[Any], batch_size: int) -> Iterator[list[str]]:
    last = ""
    while True:
        rows = conn.execute(
            """
            SELECT order_id FROM (
              SELECT order_id FROM reporting_business_scopes
              UNION
              SELECT business_ref AS order_id FROM reporting_capture_references WHERE business_ref LIKE %s
            ) required
            WHERE order_id>%s ORDER BY order_id LIMIT %s
            """,
            ("ord-%", last, batch_size),
        ).fetchall()
        if not rows:
            return
        values = [str(row[0]) for row in rows]
        yield values
        last = values[-1]


def _snapshots_by_ids(url: str, table: str, ids: Sequence[str]) -> list[tuple[Any, datetime]]:
    if not ids:
        return []
    with psycopg.connect(url) as conn:
        with conn.transaction():
            conn.execute("SET TRANSACTION READ ONLY")
            return conn.execute(
                sql.SQL("SELECT data,updated_at FROM {} WHERE id=ANY(%s)").format(sql.Identifier(table)),
                (list(ids),),
            ).fetchall()


def backfill(
    reporting_url: str,
    source_urls: Mapping[str, str],
    *,
    schema: str = "public",
    batch_size: int = 2000,
    batch_delay_ms: int = 25,
    since: datetime | None = None,
    mark_complete: bool = False,
) -> dict[str, object]:
    if batch_size < 1:
        raise ValueError("batch size must be positive")
    with psycopg.connect(reporting_url) as reporting:
        _ensure_setup(reporting, schema)
        writer = BackfillWriter(reporting, max(0, batch_delay_ms) / 1000)

        for batch in _iter_snapshots(source_urls["payment"], "payment_intent_snapshots", batch_size, since):
            writer.write("captures", CAPTURE_SQL, [row for data, updated in batch for row in capture_rows(data, updated)])

        if since is None:
            post_sales_batches = (
                _snapshots_by_ids(
                    source_urls["post_sales"],
                    "post_sales_case_snapshots",
                    sorted(set(refs) | {ref.removeprefix("psc-") for ref in refs}),
                )
                for refs in _required_refs(reporting, "psc-", batch_size)
            )
            ancillary_batches = (
                _snapshots_by_ids(source_urls["ancillary_service"], "ancillary_order_items", refs)
                for refs in _required_refs(reporting, "aoi-", batch_size)
            )
            policy_batches = (
                _snapshots_by_ids(source_urls["travel_insurance"], "policy_snapshots", refs)
                for prefix in ("aoi-", "pol-")
                for refs in _required_refs(reporting, prefix, batch_size)
            )
            order_batches = (
                _snapshots_by_ids(source_urls["journey_order"], "journey_order_snapshots", order_ids)
                for order_ids in _required_order_ids(reporting, batch_size)
            )
        else:
            post_sales_batches = _iter_snapshots(source_urls["post_sales"], "post_sales_case_snapshots", batch_size, since)
            ancillary_batches = _iter_snapshots(source_urls["ancillary_service"], "ancillary_order_items", batch_size, since)
            policy_batches = _iter_snapshots(source_urls["travel_insurance"], "policy_snapshots", batch_size, since)
            order_batches = _iter_snapshots(source_urls["journey_order"], "journey_order_snapshots", batch_size, since)

        for rows in post_sales_batches:
            writer.write("post_sales_scopes", SCOPE_SQL, [row for data, updated in rows if (row := post_sales_scope_row(data, updated))])
        for rows in ancillary_batches:
            parsed = [ancillary_rows(data, updated) for data, updated in rows]
            writer.write("ancillary_scopes", SCOPE_SQL, [scope for scope, _ in parsed if scope])
            writer.write("attachments", ATTACHMENT_SQL, [attachment for _, attachment in parsed if attachment])
        for rows in policy_batches:
            parsed = [insurance_rows(data, updated) for data, updated in rows]
            writer.write("insurance_scopes", SCOPE_SQL, [row for scopes, _ in parsed for row in scopes])
            writer.write("attachments", ATTACHMENT_SQL, [row for _, attachments in parsed for row in attachments])

        for rows in order_batches:
            parsed = [order_rows(data, updated) for data, updated in rows]
            writer.write("routes", ROUTE_SQL, [row for routes, _, _ in parsed for row in routes])
            writer.write("travelers", TRAVELER_SQL, [row for _, travelers, _ in parsed for row in travelers])
            writer.write("order_scopes", SCOPE_SQL, [scope for _, _, scope in parsed if scope])

        for source_name, table, priority in (
            ("booking_orchestration", "segment_booking_snapshots", 1),
            ("seat_assignment", "seat_allocations", 2),
        ):
            for batch in _iter_snapshots(source_urls[source_name], table, batch_size, since):
                rows = []
                for data, updated in batch:
                    parsed = seat_row(data, updated, priority)
                    if parsed:
                        rows.append(parsed)
                writer.write("seat_classes", SEAT_SQL, rows)

        totals = reporting.execute(
            """
            SELECT
              count(*) FILTER (WHERE event.event_type='PaymentCaptured' AND event.amount IS NOT NULL),
              count(capture.event_id) FILTER (WHERE event.event_type='PaymentCaptured' AND event.amount IS NOT NULL)
            FROM reporting_metric_events event
            LEFT JOIN reporting_capture_references capture ON capture.event_id=event.event_id
            """
        ).fetchone()
        if totals[0] != totals[1]:
            raise RuntimeError(f"capture reference coverage is incomplete: {totals[1]}/{totals[0]}")
        details = {
            **writer.counts,
            "captured_events": int(totals[0]),
            "referenced_captures": int(totals[1]),
            "mode": "full" if since is None else "incremental",
            "since": None if since is None else since.isoformat(),
        }
        if mark_complete:
            reporting.execute(
                """
                INSERT INTO reporting_dimension_backfills(backfill_name,completed_at,details)
                VALUES (%s,now(),%s)
                ON CONFLICT(backfill_name) DO UPDATE SET completed_at=EXCLUDED.completed_at,details=EXCLUDED.details
                """,
                (BACKFILL_NAME, Jsonb(details)),
            )
        reporting.commit()
        return details


def main() -> None:
    parser = argparse.ArgumentParser(description="Backfill authoritative Reporting revenue dimensions")
    parser.add_argument("--schema", default="public")
    parser.add_argument("--batch-size", type=int, default=2000)
    parser.add_argument("--batch-delay-ms", type=int, default=25)
    parser.add_argument("--since", help="Only rescan snapshots updated at or after this RFC3339 timestamp")
    parser.add_argument("--mark-complete", action="store_true", help="Write the 008 migration gate after coverage validation")
    args = parser.parse_args()
    reporting_url = os.environ.get("REPORTING_DATABASE_URL", os.environ.get("DATABASE_URL", "")).strip()
    if not reporting_url:
        raise RuntimeError("REPORTING_DATABASE_URL or DATABASE_URL is required")
    print(json.dumps(backfill(
        reporting_url,
        _source_urls(reporting_url, os.environ),
        schema=args.schema,
        batch_size=args.batch_size,
        batch_delay_ms=args.batch_delay_ms,
        since=None if not args.since else _timestamp(args.since, datetime.now(UTC)),
        mark_complete=args.mark_complete,
    ), sort_keys=True))


if __name__ == "__main__":
    main()
