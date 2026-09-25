import importlib.util
import json
import os
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from decimal import Decimal
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg import sql
from psycopg.conninfo import conninfo_to_dict
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

from reporting.adapters.storage.postgres import PostgresReportingApplicationService, _event_from_row
from reporting.domain import MetricAggregator, Money


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "migrations/005_gross_captured_revenue.sql"
CURRENT_MIGRATION = ROOT / "migrations/006_exclude_incomplete_captures.sql"
FUTURE_MIGRATIONS = {
    ROOT / "migrations/007_authoritative_revenue_facts.sql",
    ROOT / "migrations/008_authoritative_revenue_views.sql",
}
RECORDING = json.loads((ROOT / "tests/fixtures/gross_captured_recorded.json").read_text())
VIEWS = (
    "reporting_revenue_by_route",
    "reporting_revenue_by_seat_class",
    "reporting_revenue_breakdowns",
)
COLUMNS = (
    "event_id", "event_type", "occurred_at", "route_id", "service_date", "seat_class",
    "amount", "currency", "channel", "passenger_type", "capacity", "confirmed",
    "booking_latency_ms", "flags",
)
LOCK = "SELECT pg_try_advisory_xact_lock(hashtextextended('reporting:projection-refresh',0))"


def recorded_events(rows):
    for row in rows:
        yield _event_from_row(tuple(row[column] for column in COLUMNS))


def test_recorded_linked_payments_are_gross_captured_once():
    rows = {row["event_id"]: row for row in RECORDING["linked_rows"]}
    assert len(RECORDING["links"]) == 20
    for link in RECORDING["links"]:
        capture = rows[link["sourceEventId"]]
        recognized = rows[link["eventId"]]
        assert capture["event_type"] == "PaymentCaptured"
        assert recognized["event_type"] == "RevenueRecognized"
        assert capture["amount"] == recognized["amount"]
        assert capture["currency"] == recognized["currency"] == "CNY"
    aggregator = MetricAggregator()
    events = list(recorded_events(RECORDING["linked_rows"]))
    for event in reversed(events):
        aggregator.record(event)
        aggregator.record(event)
    at = max(event.occurred_at for event in events)
    assert aggregator.snapshot(at).revenue_per_hour == (Money("1928.59", "CNY"),)
    for dimension in ("route", "seat_class", "channel", "traveler_type", "time_period"):
        report = aggregator.revenue_report(dimension, at=at)
        assert report.total_revenue == (Money("1928.59", "CNY"),)
        assert sum(item.count for item in report.items) == 20
    assert all(item.value != "unknown" for item in aggregator.revenue_report("channel").items)


@pytest.fixture
def database():
    url = os.environ.get("REPORTING_TEST_DATABASE_URL")
    if not url:
        pytest.skip("REPORTING_TEST_DATABASE_URL must identify a local disposable PostgreSQL")
    options = conninfo_to_dict(url)
    assert options.get("host") in {"localhost", "127.0.0.1", "::1"}
    assert options.get("hostaddr", options["host"]) in {"localhost", "127.0.0.1", "::1"}
    schema = "reporting_gross_" + uuid4().hex
    with psycopg.connect(url, autocommit=True) as admin:
        admin.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
        try:
            with ConnectionPool(url, min_size=1, max_size=5, kwargs={
                "options": f"-c search_path={schema} -c statement_timeout=30000 -c lock_timeout=2000 -c work_mem=8MB",
            }) as pool:
                with pool.connection() as conn:
                    for migration in sorted((ROOT / "migrations").glob("*.sql")):
                        if migration not in {MIGRATION, CURRENT_MIGRATION, *FUTURE_MIGRATIONS}:
                            conn.execute(migration.read_text())
                yield pool
        finally:
            admin.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))


def insert_rows(conn, rows):
    with conn.cursor().copy("COPY reporting_metric_events (" + ",".join(COLUMNS) + ") FROM STDIN") as copy:
        for row in rows:
            copy.write_row(tuple(Jsonb(row[key]) if key == "flags" else row[key] for key in COLUMNS))


def migrate(conn):
    conn.execute("SET LOCAL reporting.allow_revenue_rebuild = 'on'")
    started = time.perf_counter()
    conn.execute(MIGRATION.read_text())
    return time.perf_counter() - started


def migrate_current(conn):
    conn.execute("SET LOCAL reporting.allow_revenue_rebuild = 'on'")
    conn.execute(CURRENT_MIGRATION.read_text())


def shape(conn):
    return conn.execute("""
        SELECT c.relname, a.attname, a.atttypid, a.atttypmod, a.attnotnull, a.attcollation
        FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid
        WHERE c.oid=ANY(%s::regclass[]) AND a.attnum>0 AND NOT a.attisdropped
        ORDER BY c.relname,a.attnum
    """, (list(VIEWS),)).fetchall()


def source_rows(conn):
    return conn.execute("SELECT * FROM reporting_metric_events ORDER BY event_id").fetchall()


def nodes(plan):
    yield plan
    for child in plan.get("Plans", []):
        yield from nodes(child)


def assert_reference(conn):
    expressions = {
        "route": "COALESCE(route_id, 'unknown')",
        "seat_class": "COALESCE(seat_class, 'unknown')",
        "channel": "COALESCE(channel, 'unknown')",
        "passenger_type": "COALESCE(passenger_type, 'unknown')",
        "time_period": "to_char(date_trunc('hour', occurred_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD\"T\"HH24:00:00\"Z\"')",
    }
    for dimension, expression in expressions.items():
        expected = conn.execute(f"""
            SELECT {expression}, COALESCE(currency,'USD'), SUM(COALESCE(amount,0)), COUNT(*),
                   SUM(COALESCE((flags->>'distanceKm')::numeric,0)),
                   SUM(CASE WHEN COALESCE((flags->>'ancillaryAttached')::boolean,false) THEN 1 ELSE 0 END),
                   SUM(CASE WHEN COALESCE((flags->>'insuranceAttached')::boolean,false) THEN 1 ELSE 0 END)
            FROM reporting_metric_events WHERE event_type='PaymentCaptured'
            GROUP BY 1,2 ORDER BY 1,2
        """).fetchall()
        actual = conn.execute("""
            SELECT dimension_value,currency,revenue,payment_count,distance_km,ancillary_count,insurance_count
            FROM reporting_revenue_breakdowns WHERE dimension=%s ORDER BY 1,2
        """, (dimension,)).fetchall()
        assert actual == expected


def test_migration_rebuild_schema_grants_replay_and_grouping_plan(database):
    with database.connection() as conn:
        insert_rows(conn, RECORDING["linked_rows"])
        conn.execute("GRANT SELECT ON reporting_revenue_by_route TO PUBLIC")
        conn.execute("GRANT SELECT (revenue) ON reporting_revenue_by_seat_class TO PUBLIC")
        for view in VIEWS:
            conn.execute(sql.SQL("REFRESH MATERIALIZED VIEW {}").format(sql.Identifier(view)))
        assert conn.execute("SELECT sum(revenue) FROM reporting_revenue_by_route").fetchone()[0] == Decimal("3857.18")
        before_shape, before_rows = shape(conn), source_rows(conn)
        owner = conn.execute("SELECT relowner FROM pg_class WHERE oid='reporting_revenue_by_route'::regclass").fetchone()
    with database.connection() as conn:
        with pytest.raises(psycopg.errors.RaiseException, match="requires SET LOCAL"):
            with conn.transaction():
                conn.execute(MIGRATION.read_text())
        assert conn.execute("SELECT to_regclass('reporting_revenue_by_route_005')").fetchone()[0] is None
    with database.connection() as conn:
        elapsed = migrate(conn)
        assert shape(conn) == before_shape
        assert source_rows(conn) == before_rows
        assert conn.execute("SELECT relowner FROM pg_class WHERE oid='reporting_revenue_by_route'::regclass").fetchone() == owner
        assert conn.execute("SELECT has_table_privilege('public','reporting_revenue_by_route','SELECT')").fetchone()[0]
        assert conn.execute("SELECT has_column_privilege('public','reporting_revenue_by_seat_class','revenue','SELECT')").fetchone()[0]
        assert_reference(conn)
        for view in VIEWS[:2]:
            assert conn.execute(sql.SQL("SELECT * FROM {}").format(sql.Identifier(view))).fetchall() == [(None, "CNY", Decimal("1928.59"), 20)]
        query = conn.execute("SELECT pg_get_viewdef('reporting_revenue_breakdowns'::regclass)").fetchone()[0]
        plan = conn.execute("EXPLAIN (FORMAT JSON) " + query).fetchone()[0][0]["Plan"]
        assert len([node for node in nodes(plan) if node.get("Relation Name") == "reporting_metric_events"]) == 1
        grouping_sets = [group for node in nodes(plan) for group in node.get("Grouping Sets", [])]
        keys = [key for group in grouping_sets for field in ("Hash Keys", "Group Keys") for key in group.get(field, [])]
        assert len(keys) == 5
        assert all(any("currency" in expression for expression in key) for key in keys)
        print(json.dumps({"recorded_rows": len(before_rows), "migration_seconds": elapsed, "scan_nodes": 1, "currency_group_keys": 5}))
    service = PostgresReportingApplicationService(database)
    with database.connection() as conn:
        for event in recorded_events(RECORDING["linked_rows"]):
            assert not service._save_metric_event(conn, event)
        assert source_rows(conn) == before_rows
        service._refresh_revenue_views(conn)
    report = service.revenue_report("channel", limit=1, offset=1)
    assert report.total_revenue == (Money("1928.59", "CNY"),)
    assert report.total_items == 3
    assert len(report.items) == 1
    assert (report.limit, report.offset) == (1, 1)
    beyond = service.revenue_report("channel", limit=1, offset=100)
    assert beyond.items == ()
    assert beyond.total_revenue == report.total_revenue
    assert beyond.total_items == report.total_items


def test_real_null_dimensions_hour_boundary_and_refunds(database):
    events = list(recorded_events(RECORDING["boundary_rows"]))
    assert {event.event_type for event in events} >= {"PaymentCaptured", "RefundSettled", "RevenueRecognitionReversed"}
    captures = [event for event in events if event.is_payment_captured]
    assert len(captures) == 7
    assert all(event.route_id is None and event.seat_class is None for event in captures)
    with database.connection() as conn:
        insert_rows(conn, RECORDING["boundary_rows"])
        migrate(conn)
        assert_reference(conn)
        for timezone in ("UTC", "Asia/Shanghai", "America/New_York"):
            conn.execute("SELECT set_config('TimeZone',%s,true)", (timezone,))
            conn.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY reporting_revenue_breakdowns")
            assert_reference(conn)
            buckets = conn.execute("SELECT dimension_value,payment_count FROM reporting_revenue_breakdowns WHERE dimension='time_period' ORDER BY 1").fetchall()
            assert buckets == [("2026-09-23T14:00:00Z", 5), ("2026-09-23T15:00:00Z", 2)]
    aggregator = MetricAggregator()
    for event in events:
        aggregator.record(event)
    first = min(captures, key=lambda event: event.occurred_at)
    last = max(captures, key=lambda event: event.occurred_at)
    assert aggregator.snapshot(first.occurred_at - timedelta(microseconds=1)).revenue_per_hour == ()
    assert aggregator.snapshot(first.occurred_at).revenue_per_hour == (first.amount,)
    assert aggregator.snapshot(last.occurred_at + timedelta(hours=1)).revenue_per_hour == (last.amount,)
    assert aggregator.snapshot(last.occurred_at + timedelta(hours=1, microseconds=1)).revenue_per_hour == ()


def test_incomplete_capture_is_retained_but_excluded_consistently(database):
    incomplete = dict(RECORDING["linked_rows"][0])
    incomplete.update({
        "event_id": "evt-incomplete-capture",
        "event_type": "PaymentCaptured",
        "amount": None,
        "currency": "CNY",
        "channel": "WECHAT_PAY",
    })
    rows = [*RECORDING["linked_rows"], incomplete]
    with database.connection() as conn:
        insert_rows(conn, rows)
        migrate(conn)
        assert conn.execute(
            "SELECT sum(payment_count),sum(revenue) FROM reporting_revenue_breakdowns WHERE dimension='channel'"
        ).fetchone() == (21, Decimal("1928.59"))
        before = source_rows(conn)
        migrate_current(conn)
        assert source_rows(conn) == before
        assert conn.execute(
            "SELECT sum(payment_count),sum(revenue) FROM reporting_revenue_breakdowns WHERE dimension='channel'"
        ).fetchone() == (20, Decimal("1928.59"))
        assert conn.execute(
            "SELECT count(*) FROM reporting_metric_events WHERE event_id='evt-incomplete-capture'"
        ).fetchone()[0] == 1

    aggregator = MetricAggregator()
    for event in recorded_events(rows):
        aggregator.record(event)
    report = aggregator.revenue_report("channel")
    assert sum(item.count for item in report.items) == 20
    assert report.total_revenue == (Money("1928.59", "CNY"),)


def test_empty_install_and_active_refresh_guard(database):
    with database.connection() as holder:
        assert holder.execute(LOCK).fetchone()[0]
        with database.connection() as conn:
            with pytest.raises(psycopg.errors.RaiseException, match="refresh is active"):
                with conn.transaction():
                    conn.execute(MIGRATION.read_text())
    with database.connection() as conn:
        conn.execute(MIGRATION.read_text())
        for view in VIEWS:
            conn.execute(sql.SQL("REFRESH MATERIALIZED VIEW CONCURRENTLY {}").format(sql.Identifier(view)))
            assert conn.execute(sql.SQL("SELECT count(*) FROM {}").format(sql.Identifier(view))).fetchone()[0] == 0


def test_original_views_readable_during_build_and_swap_timeout_rolls_back(database):
    with database.connection() as conn:
        insert_rows(conn, RECORDING["linked_rows"])
        for view in VIEWS:
            conn.execute(sql.SQL("REFRESH MATERIALIZED VIEW {}").format(sql.Identifier(view)))
        original_oids = conn.execute("SELECT oid FROM pg_class WHERE oid=ANY(%s::regclass[]) ORDER BY oid", (list(VIEWS),)).fetchall()
        original_rows = source_rows(conn)

    def apply():
        with database.connection() as conn:
            migrate(conn)

    with ThreadPoolExecutor(max_workers=1) as executor:
        with database.connection() as holder:
            holder.execute("LOCK TABLE reporting_metric_events IN ACCESS EXCLUSIVE MODE")
            migration = executor.submit(apply)
            with database.connection() as observer:
                deadline = time.monotonic() + 1.5
                while time.monotonic() < deadline:
                    building = observer.execute("""
                        SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid
                        WHERE l.relation='reporting_metric_events'::regclass
                          AND NOT l.granted AND a.query LIKE '%%CREATE MATERIALIZED VIEW reporting_revenue_by_route_005%%'
                    """).fetchone()[0]
                    if building:
                        break
                    time.sleep(0.01)
                assert building
                started = time.perf_counter()
                for view in VIEWS:
                    observer.execute(sql.SQL("SELECT * FROM {}").format(sql.Identifier(view))).fetchall()
                read_seconds = time.perf_counter() - started
                assert not observer.execute(LOCK).fetchone()[0]
                print(json.dumps({"reads_during_blocked_build_seconds": read_seconds}))
        migration.result(timeout=5)

        with database.connection() as reader:
            reader.execute("SELECT * FROM reporting_revenue_by_route").fetchall()
            started = time.perf_counter()
            migration = executor.submit(apply)
            with pytest.raises(psycopg.errors.LockNotAvailable):
                migration.result(timeout=5)
            print(json.dumps({"swap_lock_timeout_seconds": time.perf_counter() - started}))
        with database.connection() as conn:
            assert source_rows(conn) == original_rows
            assert conn.execute("SELECT to_regclass('reporting_revenue_by_route_005')").fetchone()[0] is None
            assert conn.execute("SELECT sum(revenue) FROM reporting_revenue_by_route").fetchone()[0] == Decimal("1928.59")
            assert conn.execute("SELECT oid FROM pg_class WHERE oid=ANY(%s::regclass[]) ORDER BY oid", (list(VIEWS),)).fetchall() != original_oids


def test_external_dependency_aborts_without_deleting_source_or_views(database):
    with database.connection() as conn:
        insert_rows(conn, RECORDING["linked_rows"])
        conn.execute("CREATE VIEW reporting_dependency AS SELECT * FROM reporting_revenue_by_route")
        before = source_rows(conn)
    with database.connection() as conn:
        with pytest.raises(psycopg.errors.DependentObjectsStillExist):
            with conn.transaction():
                migrate(conn)
        assert source_rows(conn) == before
        assert conn.execute("SELECT to_regclass('reporting_dependency')").fetchone()[0] is not None
        assert conn.execute("SELECT to_regclass('reporting_revenue_by_route_005')").fetchone()[0] is None


def test_coordinated_runner_records_exact_version_and_skips_rebuild(database):
    spec = importlib.util.spec_from_file_location("reporting_migrate_revenue", ROOT / "scripts/migrate_revenue.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    with database.connection() as conn:
        insert_rows(conn, RECORDING["linked_rows"])
        schema = conn.execute("SELECT current_schema()").fetchone()[0]
        conn.execute("CREATE TABLE schema_migrations (version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
        for path in sorted((ROOT / "migrations").glob("*.sql")):
            if path not in {MIGRATION, CURRENT_MIGRATION, *FUTURE_MIGRATIONS}:
                conn.execute("INSERT INTO schema_migrations(version) VALUES (%s)", (path.name,))
    result = module.migrate_revenue(os.environ["REPORTING_TEST_DATABASE_URL"], schema, 30)
    assert result["version"] == CURRENT_MIGRATION.name
    assert any("Reporting revenue swap took" in notice for notice in result["notices"])
    print(json.dumps(result))
    with database.connection() as conn:
        oids = conn.execute("SELECT oid FROM pg_class WHERE oid=ANY(%s::regclass[]) ORDER BY oid", (list(VIEWS),)).fetchall()
        assert conn.execute("SELECT count(*) FROM schema_migrations WHERE version=ANY(%s)", ([MIGRATION.name, CURRENT_MIGRATION.name],)).fetchone()[0] == 2
        assert_reference(conn)
    again = module.migrate_revenue(os.environ["REPORTING_TEST_DATABASE_URL"], schema, 30)
    assert again["applied_at"] == result["applied_at"]
    with database.connection() as conn:
        assert conn.execute("SELECT oid FROM pg_class WHERE oid=ANY(%s::regclass[]) ORDER BY oid", (list(VIEWS),)).fetchall() == oids
        assert conn.execute(LOCK).fetchone()[0]
