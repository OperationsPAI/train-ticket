from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from datetime import datetime

import psycopg
from psycopg import sql

sys.path.insert(0, str(Path(__file__).resolve().parent))
from backfill_revenue_dimensions import _source_urls, backfill


ROOT = Path(__file__).resolve().parents[1]
SETUP_VERSION = "007_authoritative_revenue_facts.sql"
VIEW_VERSION = "008_authoritative_revenue_views.sql"


def _apply(conn: psycopg.Connection, version: str) -> bool:
    if conn.execute("SELECT 1 FROM schema_migrations WHERE version=%s", (version,)).fetchone():
        return False
    conn.execute((ROOT / "migrations" / version).read_text())
    conn.execute("INSERT INTO schema_migrations(version) VALUES (%s)", (version,))
    return True


def migrate(
    reporting_url: str,
    *,
    schema: str = "public",
    batch_size: int = 2000,
    batch_delay_ms: int = 25,
    statement_timeout_seconds: int = 1800,
    since: datetime | None = None,
    source_env: dict[str, str] | None = None,
) -> dict[str, object]:
    started = time.perf_counter()
    with psycopg.connect(reporting_url, autocommit=True) as lock_conn:
        lock_conn.execute(sql.SQL("SET search_path TO {}").format(sql.Identifier(schema)))
        lock_conn.execute("SELECT set_config('statement_timeout',%s,false)", (str(statement_timeout_seconds * 1000),))
        lock_conn.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version text PRIMARY KEY,applied_at timestamptz NOT NULL DEFAULT now())")
        if not lock_conn.execute("SELECT pg_try_advisory_lock(hashtextextended('reporting:projection-refresh',0))").fetchone()[0]:
            raise RuntimeError("Reporting refresh is active; retry in the coordinated migration window")
        with lock_conn.transaction():
            setup_applied = _apply(lock_conn, SETUP_VERSION)
        details = backfill(
            reporting_url,
            _source_urls(reporting_url, source_env or os.environ),
            schema=schema,
            batch_size=batch_size,
            batch_delay_ms=batch_delay_ms,
            since=since,
            mark_complete=True,
        )
        with lock_conn.transaction():
            lock_conn.execute("SET LOCAL reporting.allow_revenue_rebuild='on'")
            views_applied = _apply(lock_conn, VIEW_VERSION)
        rows = lock_conn.execute(
            """
            SELECT matviewname,ispopulated FROM pg_matviews
            WHERE schemaname=current_schema() AND matviewname IN
              ('reporting_revenue_by_route','reporting_revenue_by_seat_class','reporting_revenue_breakdowns')
            ORDER BY matviewname
            """
        ).fetchall()
        if len(rows) != 3 or not all(row[1] for row in rows):
            raise RuntimeError("Expected three populated authoritative revenue views")
        return {
            "setupApplied": setup_applied,
            "viewsApplied": views_applied,
            "backfill": details,
            "elapsedSeconds": time.perf_counter() - started,
            "views": rows,
        }


def main() -> None:
    parser = argparse.ArgumentParser(description="Backfill and switch Reporting to authoritative revenue dimensions")
    parser.add_argument("--allow-rebuild", action="store_true", required=True)
    parser.add_argument("--schema", default="public")
    parser.add_argument("--batch-size", type=int, default=2000)
    parser.add_argument("--batch-delay-ms", type=int, default=25)
    parser.add_argument("--statement-timeout-seconds", type=int, default=1800)
    parser.add_argument("--since", help="Only rescan source snapshots updated at or after this RFC3339 timestamp")
    args = parser.parse_args()
    reporting_url = os.environ.get("REPORTING_DATABASE_URL", os.environ.get("DATABASE_URL", "")).strip()
    if not reporting_url:
        raise RuntimeError("REPORTING_DATABASE_URL or DATABASE_URL is required")
    print(json.dumps(migrate(
        reporting_url,
        schema=args.schema,
        batch_size=args.batch_size,
        batch_delay_ms=args.batch_delay_ms,
        statement_timeout_seconds=args.statement_timeout_seconds,
        since=None if not args.since else datetime.fromisoformat(args.since.replace("Z", "+00:00")),
    ), default=str, sort_keys=True))


if __name__ == "__main__":
    main()
