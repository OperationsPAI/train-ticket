import json
import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
import redis
from psycopg_pool import ConnectionPool

from train_ticket_platform import storage
from train_ticket_platform.storage import OutboxRelay


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL") or not os.getenv("TEST_REDIS_URL") or not os.getenv("TEST_OUTBOX_EVENTS"), reason="requires PostgreSQL, Redis and recorded events")
def test_pipeline_preserves_envelopes_order_stream_bounds_and_failure_recovery(monkeypatch):
    events = json.loads(Path(os.environ["TEST_OUTBOX_EVENTS"]).read_text())[:503]
    assert len(events) == 503
    schema = "outbox_pipeline_" + uuid4().hex
    stream = "test:outbox:" + uuid4().hex
    broken = stream + ":unavailable"
    client = redis.Redis.from_url(os.environ["TEST_REDIS_URL"], decode_responses=True)
    with psycopg.connect(os.environ["TEST_DATABASE_URL"], autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(os.environ["TEST_DATABASE_URL"], min_size=1, max_size=4, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    conn.execute("CREATE TABLE outbox(seq bigserial PRIMARY KEY,event_id text UNIQUE,stream text,envelope jsonb,published_at timestamptz)")
                    with conn.cursor() as cursor:
                        cursor.executemany("INSERT INTO outbox(event_id,stream,envelope) VALUES (%s,%s,%s::jsonb)", [(event["eventId"], stream, json.dumps(event)) for event in events])
                relay = OutboxRelay(pool, client)
                assert [relay.relay_once() for _ in range(7)] == [100, 100, 100, 100, 100, 3, 0]
                entries = client.xrange(stream)
                assert all(set(fields) == {"envelope"} for _, fields in entries)
                assert [json.loads(fields["envelope"]) for _, fields in entries] == events
                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM outbox WHERE published_at IS NULL").fetchone()[0] == 0
                    conn.execute("UPDATE outbox SET published_at=NULL WHERE seq<=3")
                    conn.execute("UPDATE outbox SET stream=%s WHERE seq=2", (broken,))
                client.set(broken, "unavailable stream")
                with pytest.raises(redis.ResponseError, match="WRONGTYPE"):
                    relay.relay_once()
                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM outbox WHERE published_at IS NULL").fetchone()[0] == 3
                    conn.execute("UPDATE outbox SET stream=%s WHERE seq=2", (stream,))
                assert relay.relay_once() == 3
                assert [json.loads(fields["envelope"]) for _, fields in client.xrevrange(stream, count=3)][::-1] == events[:3]
                with pool.connection() as conn:
                    conn.execute("UPDATE outbox SET published_at=NULL")
                monkeypatch.setattr(storage, "MAXLEN", 100)
                assert relay.relay_once(limit=503) == 503
                assert 100 <= client.xlen(stream) < 200
                assert json.loads(client.xrevrange(stream, count=1)[0][1]["envelope"]) == events[-1]
        finally:
            client.delete(stream, broken)
            client.close()
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
