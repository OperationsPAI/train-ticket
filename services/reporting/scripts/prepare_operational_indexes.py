from __future__ import annotations

import argparse
import json
import os
import time

import psycopg
from psycopg import sql


INDEXES = {
    "idx_reporting_metric_events_type_occurred": """
      CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_reporting_metric_events_type_occurred
      ON reporting_metric_events(event_type,occurred_at DESC)
    """,
    "idx_reporting_metric_events_latency_occurred": """
      CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_reporting_metric_events_latency_occurred
      ON reporting_metric_events(occurred_at DESC,booking_latency_ms)
      WHERE booking_latency_ms IS NOT NULL
    """,
    "idx_reporting_metric_events_capacity_latest": """
      CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_reporting_metric_events_capacity_latest
      ON reporting_metric_events(route_id,service_date,occurred_at DESC,event_id DESC)
      INCLUDE(capacity,confirmed)
      WHERE route_id IS NOT NULL AND service_date IS NOT NULL
        AND (capacity IS NOT NULL OR confirmed IS NOT NULL)
    """,
}


def prepare(url: str, statement_timeout_seconds: int = 1800, schema: str = "public") -> dict[str, object]:
    started = time.perf_counter()
    with psycopg.connect(url, autocommit=True) as conn:
        conn.execute(sql.SQL("SET search_path TO {}").format(sql.Identifier(schema)))
        conn.execute("SELECT set_config('statement_timeout',%s,false)", (str(statement_timeout_seconds * 1000),))
        for statement in INDEXES.values():
            conn.execute(statement)
        rows = conn.execute(
            """
            SELECT c.relname,i.indisvalid,i.indisready
            FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
            WHERE c.relname=ANY(%s) ORDER BY c.relname
            """,
            (list(INDEXES),),
        ).fetchall()
    if len(rows) != len(INDEXES) or not all(row[1] and row[2] for row in rows):
        raise RuntimeError("Reporting operational indexes are not all ready and valid")
    return {"elapsedSeconds": time.perf_counter() - started, "indexes": [row[0] for row in rows]}


def main() -> None:
    parser = argparse.ArgumentParser(description="Create Reporting operational indexes without blocking ingestion")
    parser.add_argument("--statement-timeout-seconds", type=int, default=1800)
    parser.add_argument("--schema", default="public")
    args = parser.parse_args()
    url = os.environ.get("REPORTING_DATABASE_URL", os.environ.get("DATABASE_URL", "")).strip()
    if not url:
        raise RuntimeError("REPORTING_DATABASE_URL or DATABASE_URL is required")
    print(json.dumps(prepare(url, args.statement_timeout_seconds, args.schema), sort_keys=True))


if __name__ == "__main__":
    main()
