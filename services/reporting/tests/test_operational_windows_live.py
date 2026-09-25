from __future__ import annotations

import os
import importlib.util
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg import sql
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

from reporting.adapters.storage.postgres import PostgresReportingApplicationService
from reporting.domain import Money


ROOT = Path(__file__).resolve().parents[1]
PREPARE_SPEC = importlib.util.spec_from_file_location(
    "reporting_prepare_operational_indexes",
    ROOT / "scripts/prepare_operational_indexes.py",
)
prepare_module = importlib.util.module_from_spec(PREPARE_SPEC)
assert PREPARE_SPEC.loader is not None
PREPARE_SPEC.loader.exec_module(prepare_module)


@pytest.fixture
def database():
    url = os.environ.get("REPORTING_TEST_DATABASE_URL")
    if not url:
        pytest.skip("REPORTING_TEST_DATABASE_URL must identify a local disposable PostgreSQL")
    schema = "reporting_windows_" + uuid4().hex
    with psycopg.connect(url, autocommit=True) as admin:
        admin.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
        try:
            with ConnectionPool(url, min_size=1, max_size=4, kwargs={"options": f"-c search_path={schema} -c statement_timeout=30000"}) as pool:
                with pool.connection() as conn:
                    for migration in sorted((ROOT / "migrations").glob("*.sql")):
                        conn.execute(migration.read_text())
                yield pool
        finally:
            admin.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))


def test_operational_windows_are_complete_beyond_previous_ten_thousand_cap(database):
    observed_at = datetime(2026, 9, 25, 5, 30, tzinfo=UTC)
    rows = [
        (
            f"evt-order-{index:05d}",
            "JourneyOrderCreated",
            observed_at - timedelta(seconds=index % 60),
            Jsonb({"sourceContext": "journey-order", "anomalySignal": False}),
        )
        for index in range(12_001)
    ]
    with database.connection() as conn:
        with conn.cursor() as cursor:
            cursor.executemany(
                "INSERT INTO reporting_metric_events(event_id,event_type,occurred_at,flags) VALUES (%s,%s,%s,%s)",
                rows,
            )

    snapshot = PostgresReportingApplicationService(database).operational_metrics(observed_at)

    assert snapshot.orders_per_second == 200.0167
    rollup = next(item for item in snapshot.context_rollups if item.event_type == "JourneyOrderCreated")
    assert rollup.count == 12_001
    assert snapshot.route_metrics == ()


def test_operational_windows_use_explicit_boundaries(database):
    observed_at = datetime(2026, 9, 25, 5, 30, tzinfo=UTC)
    rows = [
        ("inside-order", "JourneyOrderCreated", observed_at - timedelta(seconds=60), None, None, Jsonb({"sourceContext": "journey-order"})),
        ("outside-order", "JourneyOrderCreated", observed_at - timedelta(seconds=60, microseconds=1), None, None, Jsonb({"sourceContext": "journey-order"})),
        ("inside-refund", "RefundSettled", observed_at - timedelta(hours=24), None, None, Jsonb({"sourceContext": "payment"})),
        ("outside-refund", "RefundSettled", observed_at - timedelta(hours=24, microseconds=1), None, None, Jsonb({"sourceContext": "payment"})),
        ("capture", "PaymentCaptured", observed_at - timedelta(minutes=30), "CNY", "12.34", Jsonb({"sourceContext": "payment"})),
    ]
    with database.connection() as conn:
        with conn.cursor() as cursor:
            cursor.executemany(
                "INSERT INTO reporting_metric_events(event_id,event_type,occurred_at,currency,amount,flags) VALUES (%s,%s,%s,%s,%s,%s)",
                rows,
            )

    snapshot = PostgresReportingApplicationService(database).operational_metrics(observed_at)

    assert snapshot.orders_per_second == 0.0167
    assert snapshot.refund_rate == 0.5
    assert snapshot.revenue_per_hour[0] == Money("12.34", "CNY")


def test_operational_indexes_are_prepared_before_populated_migration():
    url = os.environ.get("REPORTING_TEST_DATABASE_URL")
    if not url:
        pytest.skip("REPORTING_TEST_DATABASE_URL must identify a local disposable PostgreSQL")
    schema = "reporting_operational_indexes_" + uuid4().hex
    migration = ROOT / "migrations/009_operational_window_indexes.sql"
    with psycopg.connect(url, autocommit=True) as admin:
        admin.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
        try:
            with psycopg.connect(url, options=f"-c search_path={schema}") as conn:
                conn.execute((ROOT / "migrations/001_reporting_storage.sql").read_text())
                conn.execute("INSERT INTO reporting_metric_events(event_id,event_type,occurred_at,flags) VALUES ('evt-1','JourneyOrderCreated',now(),'{}')")
                with pytest.raises(psycopg.errors.RaiseException, match="requires concurrent operational indexes"):
                    with conn.transaction():
                        conn.execute(migration.read_text())

            result = prepare_module.prepare(url, 30, schema)
            assert set(result["indexes"]) == set(prepare_module.INDEXES)

            with psycopg.connect(url, options=f"-c search_path={schema}") as conn:
                conn.execute(migration.read_text())
        finally:
            admin.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))


def test_route_metrics_page_is_complete_beyond_previous_ten_thousand_cap(database):
    observed_at = datetime(2026, 9, 25, 5, 30, tzinfo=UTC)
    rows = [
        (
            f"evt-route-{index:05d}",
            "JourneyOrderCreated",
            observed_at - timedelta(hours=1),
            f"seg-{index:05d}",
            observed_at.date(),
            "SECOND_CLASS",
            Jsonb({"sourceContext": "journey-order"}),
        )
        for index in range(10_001)
    ]
    with database.connection() as conn:
        with conn.cursor() as cursor:
            cursor.executemany(
                """
                INSERT INTO reporting_metric_events(event_id,event_type,occurred_at,route_id,service_date,seat_class,flags)
                VALUES (%s,%s,%s,%s,%s,%s,%s)
                """,
                rows,
            )

    service = PostgresReportingApplicationService(database)
    page, total = service.route_metrics(limit=2, offset=9_999, at=observed_at)
    beyond, beyond_total = service.route_metrics(limit=2, offset=20_000, at=observed_at)

    assert total == beyond_total == 10_001
    assert [item.route_id for item in page] == ["seg-09999", "seg-10000"]
    assert all(item.bookings == 1 and item.route_demand == 1 for item in page)
    assert beyond == ()
