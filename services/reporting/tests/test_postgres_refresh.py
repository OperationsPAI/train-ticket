import os
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg_pool import ConnectionPool
from reporting.adapters.storage.postgres import PostgresReportingApplicationService
from reporting.domain import ReadModelStatus
from train_ticket_platform.events import EventEnvelope
from reporting.adapters.messaging.subscriber import RedisEventSubscriber
from reporting.adapters.messaging.stream_config import reporting_subscription_streams


def test_subscriber_pool_covers_all_parallel_readers():
    subscriber = RedisEventSubscriber("redis://localhost:16379")
    try:
        workers = int(os.environ.get("REPORTING_CONSUMERS_PER_STREAM", "4"))
        assert subscriber._client.connection_pool.max_connections >= len(reporting_subscription_streams()) * workers
    finally:
        subscriber._client.close()


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL"), reason="TEST_DATABASE_URL is required")
def test_durable_projection_refresh_does_not_block_ingestion():
    url = os.environ["TEST_DATABASE_URL"]
    schema = "reporting_refresh_" + uuid4().hex
    with psycopg.connect(url, autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(url, max_size=8, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    for migration in sorted((Path(__file__).parents[1] / "migrations").glob("*.sql")):
                        conn.execute(migration.read_text())
                service = PostgresReportingApplicationService(pool)
                before = service.get_dashboard("dash-revenue")

                def payment(index):
                    return EventEnvelope(eventId="evt-" + str(uuid4()), eventType="PaymentCaptured",
                        occurredAt=datetime.now(UTC), correlationId="corr-" + str(uuid4()), producer="payment",
                        payload={"amount": "107.50", "currency": "CNY", "routeId": "route-1"})

                events = [payment(index) for index in range(8)]
                with pool.connection() as blocked:
                    with blocked.transaction():
                        blocked.execute("SELECT pg_advisory_xact_lock(hashtextextended('reporting:projection-refresh',0))")
                        with ThreadPoolExecutor(max_workers=4) as workers:
                            results = list(workers.map(service.handle_event, events + events))
                        assert all(result.status.value == "SUCCESS" for result in results)
                        assert service.refresh_pending_projections() == 0
                        with pool.connection() as conn:
                            assert conn.execute("SELECT count(*) FROM reporting_metric_events").fetchone()[0] == 8
                            assert conn.execute("SELECT generation,refreshed_generation FROM reporting_projection_refresh_requests").fetchone() == (8, 0)
                            assert conn.execute("SELECT count(*) FROM reporting_revenue_by_route").fetchone()[0] == 0
                        assert service.get_dashboard("dash-revenue") == before

                resumed = PostgresReportingApplicationService(pool)
                assert resumed.refresh_pending_projections() == 8
                dashboard = resumed.get_dashboard("dash-revenue")
                assert dashboard.status is ReadModelStatus.READY
                assert dashboard.current_snapshot.event_count == before.current_snapshot.event_count + 8
                assert resumed.refresh_pending_projections() == 0
                with pool.connection() as conn:
                    assert conn.execute("SELECT sum(revenue) FROM reporting_revenue_by_route").fetchone()[0] == Decimal("860.00")
                    conn.execute("DELETE FROM processed_events WHERE event_id=%s", (events[0].eventId,))
                assert resumed.handle_event(events[0]).status.value == "SUCCESS"
                assert resumed.refresh_pending_projections() == 0
                assert resumed.handle_event(payment(10)).status.value == "SUCCESS"
                with ThreadPoolExecutor(max_workers=1) as worker:
                    with pool.connection() as blocked:
                        with blocked.transaction():
                            blocked.execute("REFRESH MATERIALIZED VIEW reporting_revenue_by_route")
                            refreshing = worker.submit(resumed.refresh_pending_projections)
                            deadline = time.monotonic() + 5
                            while time.monotonic() < deadline:
                                with pool.connection() as conn:
                                    waiting = conn.execute("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query='REFRESH MATERIALIZED VIEW CONCURRENTLY reporting_revenue_by_route'").fetchone()[0]
                                if waiting:
                                    break
                                time.sleep(0.01)
                            assert waiting
                            assert resumed.handle_event(payment(11)).status.value == "SUCCESS"
                    assert refreshing.result(timeout=10) == 1
                with pool.connection() as conn:
                    assert conn.execute("SELECT sum(generation-refreshed_generation) FROM reporting_projection_refresh_requests").fetchone()[0] == 1
                assert resumed.refresh_pending_projections() == 1
                assert resumed.handle_event(payment(9)).status.value == "SUCCESS"
                resumed.start_projection_refresh()
                try:
                    deadline = time.monotonic() + 10
                    while time.monotonic() < deadline:
                        with pool.connection() as conn:
                            pending = conn.execute("SELECT sum(generation-refreshed_generation) FROM reporting_projection_refresh_requests").fetchone()[0]
                        if pending == 0:
                            break
                        time.sleep(0.05)
                    assert pending == 0
                    with pool.connection() as conn:
                        assert conn.execute("SELECT sum(revenue) FROM reporting_revenue_by_route").fetchone()[0] == Decimal("1182.50")
                finally:
                    resumed.stop_projection_refresh()
                for _ in range(3):
                    assert resumed.handle_event(EventEnvelope(eventId="evt-" + str(uuid4()), eventType="PaymentFailed",
                        occurredAt=datetime.now(UTC), correlationId="corr-" + str(uuid4()), producer="payment", payload={})).status.value == "SUCCESS"
                assert resumed.refresh_pending_projections() == 3
                with pool.connection() as conn:
                    types = {row[0] for row in conn.execute("SELECT envelope->>'eventType' FROM outbox").fetchall()}
                    assert "AnomalyDetected" in types
                    assert "UrgentNotificationRequested" in types
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
