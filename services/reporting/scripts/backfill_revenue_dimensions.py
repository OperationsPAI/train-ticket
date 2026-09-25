from __future__ import annotations

import argparse
import json
import os
import tempfile
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


def _copy_capture_references(
    source_url: str,
    target: psycopg.Connection[Any],
    since: datetime | None,
) -> int:
    target.execute(
        """
        CREATE TEMP TABLE IF NOT EXISTS reporting_capture_reference_stage (
          event_id text PRIMARY KEY,
          business_ref text NOT NULL,
          payment_intent_id text,
          payment_purpose text,
          source_occurred_at timestamptz NOT NULL
        ) ON COMMIT PRESERVE ROWS
        """
    )
    target.execute("TRUNCATE reporting_capture_reference_stage")
    where = "" if since is None else "AND snapshot.updated_at >= %s"
    params: tuple[Any, ...] = () if since is None else (since,)
    query = f"""
      COPY (
        WITH capture_rows AS (
          SELECT
          event->>'eventId' AS event_id,
          COALESCE(NULLIF(event->'payload'->>'businessRef',''),
                   NULLIF(event->'payload'->>'orderId',''),
                   NULLIF(snapshot.data->>'businessRef',''),
                   'unresolved:' || (event->>'eventId')) AS business_ref,
          COALESCE(NULLIF(event->'payload'->>'paymentIntentId',''),
                   NULLIF(event->'payload'->>'intentId',''),
                   NULLIF(snapshot.data->>'paymentIntentId','')) AS payment_intent_id,
          COALESCE(NULLIF(event->'payload'->>'purpose',''),
                   NULLIF(snapshot.data->>'purpose','')) AS payment_purpose,
          COALESCE(NULLIF(event->>'occurredAt','')::timestamptz,snapshot.updated_at) AS source_occurred_at,
          snapshot.id AS source_id
          FROM payment_intent_snapshots snapshot
          CROSS JOIN LATERAL jsonb_array_elements(COALESCE(snapshot.data->'domainEvents','[]'::jsonb)) event
          WHERE event->>'eventType'='PaymentCaptured'
            AND NULLIF(event->>'eventId','') IS NOT NULL {where}
        )
        SELECT DISTINCT ON (event_id)
          event_id,business_ref,payment_intent_id,payment_purpose,source_occurred_at
        FROM capture_rows
        ORDER BY event_id,source_occurred_at DESC,source_id DESC
      ) TO STDOUT
    """
    with psycopg.connect(source_url) as source:
        with source.transaction():
            source.execute("SET TRANSACTION READ ONLY")
            with source.cursor().copy(query, params) as copy_out:
                with target.cursor().copy(
                    "COPY reporting_capture_reference_stage(event_id,business_ref,payment_intent_id,payment_purpose,source_occurred_at) FROM STDIN"
                ) as copy_in:
                    for block in copy_out:
                        copy_in.write(block)
    row = target.execute(
        """
        WITH merged AS (
          INSERT INTO reporting_capture_references(event_id,business_ref,payment_intent_id,payment_purpose,source_occurred_at)
          SELECT event_id,business_ref,payment_intent_id,payment_purpose,source_occurred_at
          FROM reporting_capture_reference_stage
          ON CONFLICT(event_id) DO UPDATE SET
            business_ref=EXCLUDED.business_ref,
            payment_intent_id=EXCLUDED.payment_intent_id,
            payment_purpose=EXCLUDED.payment_purpose,
            source_occurred_at=EXCLUDED.source_occurred_at
          WHERE reporting_capture_references.source_occurred_at<=EXCLUDED.source_occurred_at
          RETURNING 1
        )
        SELECT (SELECT count(*) FROM reporting_capture_reference_stage),count(*) FROM merged
        """
    ).fetchone()
    target.commit()
    return int(row[0])


def _pipe_copy(
    source: psycopg.Connection[Any],
    source_statement: str,
    target: psycopg.Connection[Any],
    target_statement: str,
    source_params: tuple[Any, ...] = (),
) -> None:
    with source.cursor().copy(source_statement, source_params) as copy_out:
        with target.cursor().copy(target_statement) as copy_in:
            for block in copy_out:
                copy_in.write(block)


def _copy_target_requirements(
    target: psycopg.Connection[Any],
    target_statement: str,
    source: psycopg.Connection[Any],
    source_statement: str,
    target_params: tuple[Any, ...] = (),
) -> None:
    # Do not keep COPY OUT and COPY IN active on two independent PostgreSQL
    # connections at the same time. With a large required-ID set, each server
    # can fill its socket while the client is waiting on the other connection,
    # producing a protocol-level backpressure deadlock. Spool the compact ID
    # list to disk so the target COPY is fully drained before the source COPY
    # starts. TemporaryFile keeps memory bounded and is removed automatically.
    with tempfile.TemporaryFile() as spool:
        with target.cursor().copy(target_statement, target_params) as copy_out:
            for block in copy_out:
                spool.write(bytes(block))
        spool.seek(0)
        with source.cursor().copy(source_statement) as copy_in:
            while block := spool.read(1024 * 1024):
                copy_in.write(block)


def _merge_stage(target: psycopg.Connection[Any], statement: str) -> int:
    row = target.execute(statement).fetchone()
    target.commit()
    return int(row[0])


def _copy_post_sales_scopes(source_url: str, target: psycopg.Connection[Any]) -> int:
    target.execute(
        """
        CREATE TEMP TABLE IF NOT EXISTS reporting_scope_stage(
          business_ref text,order_id text,segment_refs text[],traveler_refs text[],source_occurred_at timestamptz
        ) ON COMMIT PRESERVE ROWS;
        TRUNCATE reporting_scope_stage
        """
    )
    target.commit()
    with psycopg.connect(source_url, autocommit=True) as source:
        source.execute(
            """
            CREATE TEMP TABLE reporting_required_business(source_id text,business_ref text,PRIMARY KEY(source_id,business_ref))
            ON COMMIT PRESERVE ROWS
            """
        )
        _copy_target_requirements(
            target,
            """
            COPY (
              SELECT regexp_replace(business_ref,'^psc-',''),business_ref
              FROM reporting_capture_references WHERE business_ref LIKE %s
              UNION
              SELECT business_ref,business_ref
              FROM reporting_capture_references WHERE business_ref LIKE %s
            ) TO STDOUT
            """,
            source,
            "COPY reporting_required_business(source_id,business_ref) FROM STDIN",
            ("psc-%", "psc-%"),
        )
        _pipe_copy(
            source,
            """
            COPY (
              SELECT required.business_ref,
                     COALESCE(NULLIF(snapshot.data->>'journeyOrderId',''),NULLIF(snapshot.data->>'orderId','')),
                     ARRAY(SELECT DISTINCT value FROM jsonb_array_elements_text(COALESCE(snapshot.data->'scope'->'segmentRefs','[]'::jsonb)) value ORDER BY value),
                     ARRAY(SELECT DISTINCT value FROM jsonb_array_elements_text(COALESCE(snapshot.data->'scope'->'travelerRefs','[]'::jsonb)) value ORDER BY value),
                     snapshot.updated_at
              FROM reporting_required_business required
              JOIN post_sales_case_snapshots snapshot ON snapshot.id=required.source_id
              WHERE COALESCE(NULLIF(snapshot.data->>'journeyOrderId',''),NULLIF(snapshot.data->>'orderId','')) IS NOT NULL
            ) TO STDOUT
            """,
            target,
            "COPY reporting_scope_stage(business_ref,order_id,segment_refs,traveler_refs,source_occurred_at) FROM STDIN",
        )
    return _merge_stage(
        target,
        """
        WITH source_rows AS (
          SELECT DISTINCT ON (business_ref)
            business_ref,order_id,segment_refs,traveler_refs,source_occurred_at
          FROM reporting_scope_stage
          ORDER BY business_ref,source_occurred_at DESC,order_id DESC
        ), merged AS (
          INSERT INTO reporting_business_scopes(business_ref,order_id,segment_refs,traveler_refs,source_occurred_at)
          SELECT business_ref,order_id,segment_refs,traveler_refs,source_occurred_at FROM source_rows
          ON CONFLICT(business_ref) DO UPDATE SET
            order_id=EXCLUDED.order_id,
            segment_refs=CASE WHEN cardinality(EXCLUDED.segment_refs)>0 THEN EXCLUDED.segment_refs ELSE reporting_business_scopes.segment_refs END,
            traveler_refs=CASE WHEN cardinality(EXCLUDED.traveler_refs)>0 THEN EXCLUDED.traveler_refs ELSE reporting_business_scopes.traveler_refs END,
            source_occurred_at=EXCLUDED.source_occurred_at
          WHERE reporting_business_scopes.source_occurred_at<=EXCLUDED.source_occurred_at
          RETURNING 1
        ) SELECT count(*) FROM merged
        """,
    )


def _prepare_required_orders(target: psycopg.Connection[Any], source: psycopg.Connection[Any]) -> None:
    source.execute(
        "CREATE TEMP TABLE reporting_required_orders(order_id text PRIMARY KEY) ON COMMIT PRESERVE ROWS"
    )
    _copy_target_requirements(
        target,
        """
        COPY (
          SELECT order_id FROM reporting_business_scopes
          UNION
          SELECT business_ref FROM reporting_capture_references WHERE business_ref LIKE %s
        ) TO STDOUT
        """,
        source,
        "COPY reporting_required_orders(order_id) FROM STDIN",
        ("ord-%",),
    )
    source.execute("ANALYZE reporting_required_orders")


def _copy_order_facts(source_url: str, target: psycopg.Connection[Any]) -> dict[str, int]:
    target.execute(
        """
        CREATE TEMP TABLE IF NOT EXISTS reporting_order_snapshot_stage(
          order_id text,source_occurred_at timestamptz,segments jsonb,travelers jsonb
        ) ON COMMIT PRESERVE ROWS;
        TRUNCATE reporting_order_snapshot_stage
        """
    )
    target.commit()
    with psycopg.connect(source_url, autocommit=True) as source:
        _prepare_required_orders(target, source)
        _pipe_copy(
            source,
            """
            COPY (
              SELECT COALESCE(NULLIF(snapshot.data->>'orderId',''),snapshot.id),snapshot.updated_at,
                     COALESCE(snapshot.data->'segments','[]'::jsonb),
                     COALESCE(snapshot.data->'travelers',snapshot.data->'travelerRefs','[]'::jsonb)
              FROM reporting_required_orders required
              JOIN journey_order_snapshots snapshot ON snapshot.id=required.order_id
            ) TO STDOUT
            """,
            target,
            "COPY reporting_order_snapshot_stage(order_id,source_occurred_at,segments,travelers) FROM STDIN",
        )
    scopes = _merge_stage(
        target,
        """
        WITH source_rows AS (
          SELECT DISTINCT ON (order_id) order_id,source_occurred_at
          FROM reporting_order_snapshot_stage
          ORDER BY order_id,source_occurred_at DESC
        ), merged AS (
          INSERT INTO reporting_business_scopes(business_ref,order_id,segment_refs,traveler_refs,source_occurred_at)
          SELECT order_id,order_id,'{}'::text[],'{}'::text[],source_occurred_at FROM source_rows
          ON CONFLICT(business_ref) DO UPDATE SET order_id=EXCLUDED.order_id,source_occurred_at=EXCLUDED.source_occurred_at
          WHERE reporting_business_scopes.source_occurred_at<=EXCLUDED.source_occurred_at
          RETURNING 1
        ) SELECT count(*) FROM merged
        """,
    )
    routes = _merge_stage(
        target,
        """
        WITH source_rows AS (
          SELECT DISTINCT ON (order_id,route_id)
            order_id,route_id,service_date,source_occurred_at
          FROM (
            SELECT stage.order_id,segment->>'segmentRef' AS route_id,
                   NULLIF(left(segment->>'departureTime',10),'')::date AS service_date,
                   stage.source_occurred_at
            FROM reporting_order_snapshot_stage stage
            CROSS JOIN LATERAL jsonb_array_elements(stage.segments) segment
            WHERE NULLIF(segment->>'segmentRef','') IS NOT NULL
          ) routes
          ORDER BY order_id,route_id,source_occurred_at DESC
        ), merged AS (
          INSERT INTO reporting_order_routes(order_id,route_id,service_date,source_occurred_at)
          SELECT order_id,route_id,service_date,source_occurred_at FROM source_rows
          ON CONFLICT(order_id,route_id) DO UPDATE SET service_date=EXCLUDED.service_date,source_occurred_at=EXCLUDED.source_occurred_at
          WHERE reporting_order_routes.source_occurred_at<=EXCLUDED.source_occurred_at
          RETURNING 1
        ) SELECT count(*) FROM merged
        """,
    )
    travelers = _merge_stage(
        target,
        """
        WITH source_rows AS (
          SELECT DISTINCT ON (order_id,traveler_ref)
            order_id,traveler_ref,traveler_type,source_occurred_at
          FROM (
            SELECT stage.order_id,traveler->>'travelerId' AS traveler_ref,
                   traveler->>'travelerType' AS traveler_type,stage.source_occurred_at
            FROM reporting_order_snapshot_stage stage
            CROSS JOIN LATERAL jsonb_array_elements(stage.travelers) traveler
            WHERE NULLIF(traveler->>'travelerId','') IS NOT NULL
              AND NULLIF(traveler->>'travelerType','') IS NOT NULL
          ) travelers
          ORDER BY order_id,traveler_ref,source_occurred_at DESC,traveler_type DESC
        ), merged AS (
          INSERT INTO reporting_order_travelers(order_id,traveler_ref,traveler_type,source_occurred_at)
          SELECT order_id,traveler_ref,traveler_type,source_occurred_at FROM source_rows
          ON CONFLICT(order_id,traveler_ref) DO UPDATE SET traveler_type=EXCLUDED.traveler_type,source_occurred_at=EXCLUDED.source_occurred_at
          WHERE reporting_order_travelers.source_occurred_at<=EXCLUDED.source_occurred_at
          RETURNING 1
        ) SELECT count(*) FROM merged
        """,
    )
    return {"order_scopes": scopes, "routes": routes, "travelers": travelers}


def _copy_ancillary_facts(source_url: str, target: psycopg.Connection[Any]) -> dict[str, int]:
    target.execute(
        """
        CREATE TEMP TABLE IF NOT EXISTS reporting_ancillary_stage(
          item_id text,order_id text,segment_ref text,traveler_ref text,attachment_type text,purchased boolean,source_occurred_at timestamptz
        ) ON COMMIT PRESERVE ROWS;
        TRUNCATE reporting_ancillary_stage
        """
    )
    target.commit()
    with psycopg.connect(source_url, autocommit=True) as source:
        _prepare_required_orders(target, source)
        source.execute(
            "CREATE TEMP TABLE reporting_required_items(item_id text PRIMARY KEY) ON COMMIT PRESERVE ROWS"
        )
        _copy_target_requirements(
            target,
            "COPY (SELECT DISTINCT business_ref FROM reporting_capture_references WHERE business_ref LIKE %s) TO STDOUT",
            source,
            "COPY reporting_required_items(item_id) FROM STDIN",
            ("aoi-%",),
        )
        source.execute("ANALYZE reporting_required_items")
        _pipe_copy(
            source,
            """
            COPY (
              WITH candidates AS (
                SELECT snapshot.* FROM ancillary_order_items snapshot JOIN reporting_required_items item ON item.item_id=snapshot.id
                UNION ALL
                SELECT snapshot.* FROM ancillary_order_items snapshot JOIN reporting_required_orders required
                  ON COALESCE(NULLIF(snapshot.data->>'journeyOrderId',''),NULLIF(snapshot.data->>'orderId',''))=required.order_id
              )
              SELECT DISTINCT ON (id) id,
                     COALESCE(NULLIF(data->>'journeyOrderId',''),NULLIF(data->>'orderId','')),
                     data->>'segmentRef',data->>'travelerRef',
                     CASE WHEN upper(COALESCE(NULLIF(data->>'serviceType',''),data->'catalogSnapshot'->>'serviceType',''))='INSURANCE'
                          THEN 'INSURANCE' ELSE 'ANCILLARY' END,
                     (NULLIF(data->>'paymentIntentId','') IS NOT NULL OR NULLIF(data->>'paymentCapturedAt','') IS NOT NULL
                       OR data->>'status' IN ('CONFIRMED','FULFILLMENT_READY','FULFILLED','REFUND_PENDING','REFUNDED')),
                     updated_at
              FROM candidates
              WHERE COALESCE(NULLIF(data->>'journeyOrderId',''),NULLIF(data->>'orderId','')) IS NOT NULL
              ORDER BY id,updated_at DESC
            ) TO STDOUT
            """,
            target,
            "COPY reporting_ancillary_stage(item_id,order_id,segment_ref,traveler_ref,attachment_type,purchased,source_occurred_at) FROM STDIN",
        )
    scopes = _merge_stage(
        target,
        """
        WITH merged AS (
          INSERT INTO reporting_business_scopes(business_ref,order_id,segment_refs,traveler_refs,source_occurred_at)
          SELECT item_id,order_id,CASE WHEN segment_ref IS NULL THEN '{}' ELSE ARRAY[segment_ref] END,
                 CASE WHEN traveler_ref IS NULL THEN '{}' ELSE ARRAY[traveler_ref] END,source_occurred_at
          FROM reporting_ancillary_stage
          ON CONFLICT(business_ref) DO UPDATE SET order_id=EXCLUDED.order_id,
            segment_refs=CASE WHEN cardinality(EXCLUDED.segment_refs)>0 THEN EXCLUDED.segment_refs ELSE reporting_business_scopes.segment_refs END,
            traveler_refs=CASE WHEN cardinality(EXCLUDED.traveler_refs)>0 THEN EXCLUDED.traveler_refs ELSE reporting_business_scopes.traveler_refs END,
            source_occurred_at=EXCLUDED.source_occurred_at
          WHERE reporting_business_scopes.source_occurred_at<=EXCLUDED.source_occurred_at RETURNING 1
        ) SELECT count(*) FROM merged
        """,
    )
    attachments = _merge_stage(
        target,
        """
        WITH source_rows AS (
          SELECT DISTINCT ON (order_id,attachment_type)
            order_id,attachment_type,source_occurred_at
          FROM reporting_ancillary_stage
          WHERE purchased
          ORDER BY order_id,attachment_type,source_occurred_at DESC,item_id DESC
        ), merged AS (
          INSERT INTO reporting_order_attachments(order_id,attachment_type,source_occurred_at)
          SELECT order_id,attachment_type,source_occurred_at FROM source_rows
          ON CONFLICT(order_id,attachment_type) DO UPDATE SET source_occurred_at=EXCLUDED.source_occurred_at
          WHERE reporting_order_attachments.source_occurred_at<=EXCLUDED.source_occurred_at RETURNING 1
        ) SELECT count(*) FROM merged
        """,
    )
    return {"ancillary_scopes": scopes, "attachments": attachments}


def _copy_insurance_facts(source_url: str, target: psycopg.Connection[Any]) -> dict[str, int]:
    target.execute(
        """
        CREATE TEMP TABLE IF NOT EXISTS reporting_insurance_stage(
          policy_id text,ancillary_id text,order_id text,segment_refs text[],traveler_ref text,source_occurred_at timestamptz
        ) ON COMMIT PRESERVE ROWS;
        TRUNCATE reporting_insurance_stage
        """
    )
    target.commit()
    with psycopg.connect(source_url, autocommit=True) as source:
        source.execute(
            "CREATE TEMP TABLE reporting_required_policies(business_ref text PRIMARY KEY) ON COMMIT PRESERVE ROWS"
        )
        _copy_target_requirements(
            target,
            """
            COPY (
              SELECT business_ref FROM reporting_capture_references WHERE business_ref LIKE %s
              UNION
              SELECT business_ref FROM reporting_capture_references WHERE business_ref LIKE %s
            ) TO STDOUT
            """,
            source,
            "COPY reporting_required_policies(business_ref) FROM STDIN",
            ("pol-%", "aoi-%"),
        )
        _pipe_copy(
            source,
            """
            COPY (
              WITH candidates AS (
                SELECT snapshot.* FROM policy_snapshots snapshot JOIN reporting_required_policies required
                  ON snapshot.id=required.business_ref
                UNION ALL
                SELECT snapshot.* FROM policy_snapshots snapshot JOIN reporting_required_policies required
                  ON snapshot.data->>'policyId'=required.business_ref
                UNION ALL
                SELECT snapshot.* FROM policy_snapshots snapshot JOIN reporting_required_policies required
                  ON snapshot.data->>'ancillaryOrderItemId'=required.business_ref
              )
              SELECT DISTINCT ON (id)
                     COALESCE(NULLIF(data->>'policyId',''),id),data->>'ancillaryOrderItemId',
                     COALESCE(NULLIF(data->>'journeyOrderId',''),NULLIF(data->>'orderId','')),
                     ARRAY(SELECT DISTINCT value FROM jsonb_array_elements_text(COALESCE(data->'segmentRefs','[]'::jsonb)) value ORDER BY value),
                     data->>'travelerRef',updated_at
              FROM candidates
              WHERE COALESCE(NULLIF(data->>'journeyOrderId',''),NULLIF(data->>'orderId','')) IS NOT NULL
              ORDER BY id,updated_at DESC
            ) TO STDOUT
            """,
            target,
            "COPY reporting_insurance_stage(policy_id,ancillary_id,order_id,segment_refs,traveler_ref,source_occurred_at) FROM STDIN",
        )
        _merge_stage(
            target,
            """
            WITH source_rows AS (
              SELECT policy_id AS business_ref,order_id,segment_refs,CASE WHEN traveler_ref IS NULL THEN '{}' ELSE ARRAY[traveler_ref] END traveler_refs,source_occurred_at
              FROM reporting_insurance_stage WHERE policy_id IS NOT NULL
              UNION ALL
              SELECT ancillary_id,order_id,segment_refs,CASE WHEN traveler_ref IS NULL THEN '{}' ELSE ARRAY[traveler_ref] END,source_occurred_at
              FROM reporting_insurance_stage WHERE ancillary_id IS NOT NULL
            ), merged AS (
              INSERT INTO reporting_business_scopes(business_ref,order_id,segment_refs,traveler_refs,source_occurred_at)
              SELECT DISTINCT ON (business_ref) business_ref,order_id,segment_refs,traveler_refs,source_occurred_at
              FROM source_rows ORDER BY business_ref,source_occurred_at DESC
              ON CONFLICT(business_ref) DO UPDATE SET order_id=EXCLUDED.order_id,
                segment_refs=CASE WHEN cardinality(EXCLUDED.segment_refs)>0 THEN EXCLUDED.segment_refs ELSE reporting_business_scopes.segment_refs END,
                traveler_refs=CASE WHEN cardinality(EXCLUDED.traveler_refs)>0 THEN EXCLUDED.traveler_refs ELSE reporting_business_scopes.traveler_refs END,
                source_occurred_at=EXCLUDED.source_occurred_at
              WHERE reporting_business_scopes.source_occurred_at<=EXCLUDED.source_occurred_at RETURNING 1
            ) SELECT count(*) FROM merged
            """,
        )
        target.execute("TRUNCATE reporting_insurance_stage")
        target.commit()
        _prepare_required_orders(target, source)
        _pipe_copy(
            source,
            """
            COPY (
              SELECT COALESCE(NULLIF(snapshot.data->>'policyId',''),snapshot.id),
                     snapshot.data->>'ancillaryOrderItemId',
                     COALESCE(NULLIF(snapshot.data->>'journeyOrderId',''),NULLIF(snapshot.data->>'orderId','')),
                     ARRAY(SELECT DISTINCT value FROM jsonb_array_elements_text(COALESCE(snapshot.data->'segmentRefs','[]'::jsonb)) value ORDER BY value),
                     snapshot.data->>'travelerRef',snapshot.updated_at
              FROM policy_snapshots snapshot JOIN reporting_required_orders required
                ON COALESCE(NULLIF(snapshot.data->>'journeyOrderId',''),NULLIF(snapshot.data->>'orderId',''))=required.order_id
            ) TO STDOUT
            """,
            target,
            "COPY reporting_insurance_stage(policy_id,ancillary_id,order_id,segment_refs,traveler_ref,source_occurred_at) FROM STDIN",
        )
    scopes = _merge_stage(
        target,
        """
        WITH source_rows AS (
          SELECT policy_id AS business_ref,order_id,segment_refs,CASE WHEN traveler_ref IS NULL THEN '{}' ELSE ARRAY[traveler_ref] END traveler_refs,source_occurred_at
          FROM reporting_insurance_stage WHERE policy_id IS NOT NULL
          UNION ALL
          SELECT ancillary_id,order_id,segment_refs,CASE WHEN traveler_ref IS NULL THEN '{}' ELSE ARRAY[traveler_ref] END,source_occurred_at
          FROM reporting_insurance_stage WHERE ancillary_id IS NOT NULL
        ), merged AS (
          INSERT INTO reporting_business_scopes(business_ref,order_id,segment_refs,traveler_refs,source_occurred_at)
          SELECT DISTINCT ON (business_ref) business_ref,order_id,segment_refs,traveler_refs,source_occurred_at
          FROM source_rows ORDER BY business_ref,source_occurred_at DESC
          ON CONFLICT(business_ref) DO UPDATE SET order_id=EXCLUDED.order_id,
            segment_refs=CASE WHEN cardinality(EXCLUDED.segment_refs)>0 THEN EXCLUDED.segment_refs ELSE reporting_business_scopes.segment_refs END,
            traveler_refs=CASE WHEN cardinality(EXCLUDED.traveler_refs)>0 THEN EXCLUDED.traveler_refs ELSE reporting_business_scopes.traveler_refs END,
            source_occurred_at=EXCLUDED.source_occurred_at
          WHERE reporting_business_scopes.source_occurred_at<=EXCLUDED.source_occurred_at RETURNING 1
        ) SELECT count(*) FROM merged
        """,
    )
    attachments = _merge_stage(
        target,
        """
        WITH source_rows AS (
          SELECT DISTINCT ON (order_id)
            order_id,source_occurred_at
          FROM reporting_insurance_stage
          ORDER BY order_id,source_occurred_at DESC,policy_id DESC
        ), merged AS (
          INSERT INTO reporting_order_attachments(order_id,attachment_type,source_occurred_at)
          SELECT order_id,'INSURANCE',source_occurred_at FROM source_rows
          ON CONFLICT(order_id,attachment_type) DO UPDATE SET source_occurred_at=EXCLUDED.source_occurred_at
          WHERE reporting_order_attachments.source_occurred_at<=EXCLUDED.source_occurred_at RETURNING 1
        ) SELECT count(*) FROM merged
        """,
    )
    return {"insurance_scopes": scopes, "attachments": attachments}


def _copy_seat_facts(source_url: str, table: str, priority: int, target: psycopg.Connection[Any]) -> int:
    target.execute(
        """
        CREATE TEMP TABLE IF NOT EXISTS reporting_seat_stage(
          order_id text,segment_ref text,traveler_ref text,seat_class text,source_priority integer,source_occurred_at timestamptz
        ) ON COMMIT PRESERVE ROWS;
        TRUNCATE reporting_seat_stage
        """
    )
    target.commit()
    with psycopg.connect(source_url, autocommit=True) as source:
        _prepare_required_orders(target, source)
        query = sql.SQL(
            """
            COPY (
              SELECT COALESCE(NULLIF(snapshot.data->>'journeyOrderId',''),NULLIF(snapshot.data->>'orderId','')),
                     snapshot.data->>'segmentRef',snapshot.data->>'travelerRef',
                     COALESCE(NULLIF(snapshot.data->>'requestedClassRef',''),NULLIF(snapshot.data->>'classRef','')),
                     {priority},snapshot.updated_at
              FROM {table} snapshot JOIN reporting_required_orders required
                ON COALESCE(NULLIF(snapshot.data->>'journeyOrderId',''),NULLIF(snapshot.data->>'orderId',''))=required.order_id
              WHERE COALESCE(NULLIF(snapshot.data->>'requestedClassRef',''),NULLIF(snapshot.data->>'classRef','')) IS NOT NULL
                AND NULLIF(snapshot.data->>'segmentRef','') IS NOT NULL
                AND NULLIF(snapshot.data->>'travelerRef','') IS NOT NULL
            ) TO STDOUT
            """
        ).format(table=sql.Identifier(table), priority=sql.Literal(priority))
        _pipe_copy(
            source,
            query.as_string(source),
            target,
            "COPY reporting_seat_stage(order_id,segment_ref,traveler_ref,seat_class,source_priority,source_occurred_at) FROM STDIN",
        )
    return _merge_stage(
        target,
        """
        WITH source_rows AS (
          SELECT DISTINCT ON (order_id,segment_ref,traveler_ref)
            order_id,segment_ref,traveler_ref,seat_class,source_priority,source_occurred_at
          FROM reporting_seat_stage
          ORDER BY order_id,segment_ref,traveler_ref,source_occurred_at DESC,seat_class DESC
        ), merged AS (
          INSERT INTO reporting_seat_classes(order_id,segment_ref,traveler_ref,seat_class,source_priority,source_occurred_at)
          SELECT order_id,segment_ref,traveler_ref,seat_class,source_priority,source_occurred_at FROM source_rows
          ON CONFLICT(order_id,segment_ref,traveler_ref) DO UPDATE SET seat_class=EXCLUDED.seat_class,
            source_priority=EXCLUDED.source_priority,source_occurred_at=EXCLUDED.source_occurred_at
          WHERE (reporting_seat_classes.source_priority,reporting_seat_classes.source_occurred_at)
             <=(EXCLUDED.source_priority,EXCLUDED.source_occurred_at) RETURNING 1
        ) SELECT count(*) FROM merged
        """,
    )


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

        writer.counts["captures"] = _copy_capture_references(source_urls["payment"], reporting, since)

        if since is None:
            writer.counts["post_sales_scopes"] = _copy_post_sales_scopes(
                source_urls["post_sales"], reporting
            )
            for key, count in _copy_insurance_facts(source_urls["travel_insurance"], reporting).items():
                writer.counts[key] = writer.counts.get(key, 0) + count
            for key, count in _copy_ancillary_facts(source_urls["ancillary_service"], reporting).items():
                writer.counts[key] = writer.counts.get(key, 0) + count
            writer.counts.update(_copy_order_facts(source_urls["journey_order"], reporting))
            writer.counts["seat_classes"] = _copy_seat_facts(
                source_urls["booking_orchestration"],
                "segment_booking_snapshots",
                1,
                reporting,
            )
            writer.counts["seat_classes"] += _copy_seat_facts(
                source_urls["seat_assignment"],
                "seat_allocations",
                2,
                reporting,
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
