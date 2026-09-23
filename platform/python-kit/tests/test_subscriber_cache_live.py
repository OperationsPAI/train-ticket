import json
import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
import redis

from train_ticket_platform.messaging import RedisEventSubscriber


@pytest.mark.skipif(not os.getenv("TEST_RECORDED_EVENTS") or not os.getenv("TEST_DATABASE_URL") or not os.getenv("TEST_REDIS_URL"), reason="requires recorded events, PostgreSQL and Redis")
def test_subscriber_cache_is_bounded_and_evicted_replay_is_deduplicated_in_database():
    records = json.loads(Path(os.environ["TEST_RECORDED_EVENTS"]).read_text())
    envelopes = [dict(zip(fields[::2], fields[1::2]))["envelope"] for _, fields in records]
    assert len(envelopes) > RedisEventSubscriber.DEDUP_CACHE_LIMIT
    client = redis.Redis.from_url(os.environ["TEST_REDIS_URL"], decode_responses=True)
    subscriber = RedisEventSubscriber(redis_client=client)
    schema = "subscriber_cache_" + uuid4().hex
    stream = "test:" + schema
    group = "cache-test"
    with psycopg.connect(os.environ["TEST_DATABASE_URL"], autocommit=True) as database:
        database.execute(f"CREATE SCHEMA {schema}")
        database.execute(f"SET search_path TO {schema}")
        database.execute("CREATE TABLE effects(event_id text PRIMARY KEY)")
        handled = 0

        def handler(envelope):
            nonlocal handled
            handled += 1
            with database.transaction():
                database.execute("INSERT INTO effects VALUES (%s) ON CONFLICT DO NOTHING", (envelope.eventId,))

        try:
            client.xgroup_create(stream, group, "0", mkstream=True)
            for start in range(0, len(envelopes), 100):
                with client.pipeline() as batch:
                    for envelope in envelopes[start:start + 100]:
                        batch.xadd(stream, {"envelope": envelope})
                    batch.execute()
                messages = client.xreadgroup(group, "reader", {stream: ">"}, count=100)
                subscriber._process_results(messages, group, "reader", handler)
                assert len(subscriber._dedup) <= subscriber.DEDUP_CACHE_LIMIT
            first = json.loads(envelopes[0])["eventId"]
            assert not subscriber._already_seen(first)
            client.xadd(stream, {"envelope": envelopes[0]})
            subscriber._process_results(client.xreadgroup(group, "reader", {stream: ">"}, count=1), group, "reader", handler)
            assert handled == len(envelopes) + 1
            assert database.execute("SELECT count(*) FROM effects").fetchone()[0] == len(envelopes)
            assert client.xpending(stream, group)["pending"] == 0
        finally:
            client.delete(stream)
            client.close()
            database.execute("SET search_path TO public")
            database.execute(f"DROP SCHEMA {schema} CASCADE")
