import json
import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
import redis
from psycopg_pool import ConnectionPool
from reporting.adapters.messaging.subscriber import RedisEventSubscriber
from reporting.adapters.storage.postgres import PostgresReportingApplicationService
from reporting.application.service import reporting_applies_event_type


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL") or not os.getenv("TEST_REPORTING_EVENTS_FILE"), reason="PostgreSQL and actual event samples are required")
def test_irrelevant_events_ack_in_one_batch_and_failed_projection_remains_pending():
    events = json.loads(Path(os.environ["TEST_REPORTING_EVENTS_FILE"]).read_text())
    ignored = [event for event in events if not reporting_applies_event_type(event["eventType"])][:100]
    payment = next(event for event in events if event["eventType"] == "PaymentCaptured")
    assert len(ignored) == 100
    commands = []

    class ObservedRedis(redis.Redis):
        def execute_command(self, *args, **kwargs):
            commands.append(args)
            return super().execute_command(*args, **kwargs)

    client = ObservedRedis.from_url(os.environ.get("TEST_REDIS_URL", "redis://localhost:16379"), decode_responses=True)
    stream = "reporting-batch-" + uuid4().hex
    group = "reporting"
    schema = "reporting_batch_" + uuid4().hex
    with psycopg.connect(os.environ["TEST_DATABASE_URL"], autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(os.environ["TEST_DATABASE_URL"], max_size=4, kwargs={"options": f"-c search_path={schema} -c lock_timeout=100ms"}) as pool:
                with pool.connection() as conn:
                    for migration in sorted((Path(__file__).parents[1] / "migrations").glob("*.sql")):
                        conn.execute(migration.read_text())
                service = PostgresReportingApplicationService(pool)
                subscriber = RedisEventSubscriber(redis_client=client)
                client.xgroup_create(stream, group, id="0-0", mkstream=True)
                for event in ignored:
                    client.xadd(stream, {"envelope": json.dumps(event)})
                entries = client.xreadgroup(group, "reader", {stream: ">"}, count=100)
                commands.clear()
                subscriber._process_results(entries, group, "reader", service.handle_event)
                assert len(commands) == 1
                assert commands[0][0] == "XACK"
                assert len(commands[0]) == 103
                assert client.xpending(stream, group)["pending"] == 0
                payment_id = client.xadd(stream, {"envelope": json.dumps(payment)})
                entries = client.xreadgroup(group, "reader", {stream: ">"}, count=100)
                with pool.connection() as blocker:
                    blocker.execute("LOCK TABLE reporting_metric_events IN ACCESS EXCLUSIVE MODE")
                    subscriber._process_results(entries, group, "reader", service.handle_event)
                    assert client.xpending(stream, group)["pending"] == 1
                    blocker.rollback()
                subscriber._recover_idle_pending((stream,), group, "recovery", service.handle_event, min_idle_ms=0)
                assert client.xpending(stream, group)["pending"] == 0
                with pool.connection() as conn:
                    assert conn.execute("SELECT event_id FROM reporting_metric_events").fetchall() == [(payment["eventId"],)]
                    assert conn.execute("SELECT sum(generation) FROM reporting_projection_refresh_requests").fetchone()[0] == 1
                client.xadd(stream, {"envelope": "{"})
                entries = client.xreadgroup(group, "reader", {stream: ">"}, count=100)
                subscriber._process_results(entries, group, "reader", service.handle_event)
                assert client.xlen(stream + ":dlq") == 1
                assert client.xpending(stream, group)["pending"] == 0
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
            client.delete(stream, stream + ":dlq")
            client.close()
