import argparse
import json
import os
import time
from pathlib import Path

from psycopg import sql
from psycopg_pool import ConnectionPool
from train_ticket_platform.storage import run_migrations


VERSION = "005_gross_captured_revenue.sql"


def migrate_revenue(url: str, schema: str = "public", timeout_seconds: int = 600) -> dict:
    if timeout_seconds < 1:
        raise ValueError("statement timeout must be positive")
    started = time.perf_counter()
    notices = []
    with ConnectionPool(url, min_size=1, max_size=1, kwargs={
        "options": f"-c reporting.allow_revenue_rebuild=on -c statement_timeout={timeout_seconds * 1000} -c lock_timeout=2000 -c application_name=reporting-revenue-migration",
    }) as pool:
        with pool.connection() as conn:
            conn.add_notice_handler(lambda diagnostic: notices.append(diagnostic.message_primary))
            conn.execute(sql.SQL("SET search_path TO {}").format(sql.Identifier(schema)))
            conn.execute("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            acquired = conn.execute("SELECT pg_try_advisory_lock(hashtextextended('reporting:projection-refresh',0))").fetchone()[0]
            if not acquired:
                raise RuntimeError("Reporting refresh is active; retry when the coordinated window is available")
        run_migrations(pool, Path(__file__).resolve().parents[1] / "migrations")
        with pool.connection() as conn:
            version, applied_at = conn.execute("SELECT version,applied_at FROM schema_migrations WHERE version=%s", (VERSION,)).fetchone()
            populated = conn.execute("""
                SELECT matviewname,ispopulated FROM pg_matviews
                WHERE schemaname=current_schema() AND matviewname IN
                  ('reporting_revenue_by_route','reporting_revenue_by_seat_class','reporting_revenue_breakdowns')
                ORDER BY matviewname
            """).fetchall()
            if len(populated) != 3 or not all(row[1] for row in populated):
                raise RuntimeError("Expected three populated reporting revenue views")
        return {"version": version, "applied_at": applied_at.isoformat(),
                "elapsed_seconds": time.perf_counter() - started, "views": populated,
                "notices": notices}


def main() -> None:
    parser = argparse.ArgumentParser(description="Apply reporting migrations in a coordinated revenue rebuild window")
    parser.add_argument("--allow-rebuild", action="store_true", required=True)
    parser.add_argument("--schema", default="public")
    parser.add_argument("--statement-timeout-seconds", type=int, default=600)
    args = parser.parse_args()
    print(json.dumps(migrate_revenue(os.environ["DATABASE_URL"], args.schema, args.statement_timeout_seconds)))


if __name__ == "__main__":
    main()
