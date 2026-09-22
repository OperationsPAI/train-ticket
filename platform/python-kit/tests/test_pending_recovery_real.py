import os
import json
import time
from uuid import uuid4

import pytest
import redis
from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.messaging import RedisEventSubscriber


@pytest.mark.skipif(not os.getenv("TEST_REDIS_URL"), reason="TEST_REDIS_URL is required")
def test_pruning_preserves_pending_ownership_and_idle_recovery_processes_entries():
    client = redis.Redis.from_url(os.environ["TEST_REDIS_URL"], decode_responses=True)
    stream = "test:pending:" + uuid4().hex
    group = "pending-test"
    subscriber = RedisEventSubscriber(redis_client=client)
    try:
        client.xgroup_create(stream, group, "0", mkstream=True)
        event = EventEnvelope(eventType="PendingRecoveryVerified", producer="recovery-test")
        entry_id = client.xadd(stream, {"envelope": json.dumps(event.to_dict())})
        client.xreadgroup(group, "prior-owner", {stream: ">"}, count=1)
        subscriber._prune_dead_consumers((stream,), group, "current-owner")
        assert client.xpending(stream, group)["pending"] == 1
        observed = []
        subscriber._recover_idle_pending((stream,), group, "current-owner", lambda item: observed.append(item.eventId), 60_000)
        assert observed == []
        assert client.xpending_range(stream, group, entry_id, entry_id, 1)[0]["consumer"] == "prior-owner"
        time.sleep(0.02)
        subscriber._recover_idle_pending((stream,), group, "current-owner", lambda item: observed.append(item.eventId), 10)
        assert observed == [event.eventId]
        assert client.xpending(stream, group)["pending"] == 0
        subscriber._recover_idle_pending((stream,), group, "current-owner", lambda item: observed.append(item.eventId), 10)
        assert observed == [event.eventId]
    finally:
        client.delete(stream)
        client.close()
