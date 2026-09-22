import os
import threading
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import psycopg
import pytest
import redis
from psycopg_pool import ConnectionPool

from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.storage import OutboxAppender, OutboxRelay


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL") or not os.getenv("TEST_REDIS_URL"), reason="PostgreSQL and Redis URLs are required")
def test_concurrent_relays_claim_distinct_rows():
    url = os.environ["TEST_DATABASE_URL"]
    schema = "relay_test_" + uuid4().hex
    producer = "relay-test-" + uuid4().hex
    stream = "events:" + producer
    client = redis.Redis.from_url(os.environ["TEST_REDIS_URL"], decode_responses=True)
    with psycopg.connect(url, autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(url, max_size=8, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    conn.execute("CREATE TABLE outbox(seq bigserial PRIMARY KEY,event_id text UNIQUE,stream text,envelope jsonb,created_at timestamptz DEFAULT now(),published_at timestamptz)")
                    for _ in range(80):
                        OutboxAppender().append(conn, EventEnvelope(eventType="RelayConcurrencyChecked", producer=producer))
                barrier = threading.Barrier(8)

                def publish(index):
                    barrier.wait(timeout=5)
                    return OutboxRelay(pool, client).relay_once(limit=10)

                with ThreadPoolExecutor(max_workers=8) as workers:
                    assert sum(workers.map(publish, range(8))) == 80
                assert client.xlen(stream) == 80
                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM outbox WHERE published_at IS NULL").fetchone()[0] == 0
        finally:
            client.delete(stream)
            client.close()
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
