import json
import os
from pathlib import Path
from uuid import uuid4

import pytest
import redis

from fare_pricing.adapters.messaging.subscriber import RedisEventSubscriber, handles_fare_event
from train_ticket_platform.messaging import TransientHandlerError


@pytest.mark.skipif(not os.getenv("TEST_REDIS_URL") or not os.getenv("TEST_FARE_FILTER_EVENTS"), reason="requires Redis and recorded fare dependency events")
def test_fare_filter_preserves_capacity_eligibility_retry_and_invalid_envelopes():
    events = json.loads(Path(os.environ["TEST_FARE_FILTER_EVENTS"]).read_text())
    client = redis.Redis.from_url(os.environ["TEST_REDIS_URL"], decode_responses=True)
    subscriber = RedisEventSubscriber(redis_client=client)
    stream = "test:fare-filter:" + uuid4().hex
    group = "fare-filter"
    selected = {event["eventType"]: event for event in events if handles_fare_event(event["producer"], event["eventType"])}
    assert set(selected) == {"CapacitySnapshotUpdated", "EligibilityCertificateRegistered", "EligibilityCertificateVerified", "EligibilityUsageReserved", "EligibilityUsageConfirmed", "EligibilityUsageReleased"}
    ignored = [event for event in events if subscriber._ignored_entry({"envelope": json.dumps(event)})][:100]
    assert len(ignored) == 100
    calls = []
    retry_id = selected["EligibilityUsageReserved"]["eventId"]

    def handle(envelope):
        calls.append(envelope.eventId)
        if envelope.eventId == retry_id and calls.count(retry_id) == 1:
            raise TransientHandlerError("dependency unavailable")

    try:
        client.xgroup_create(stream, group, "0", mkstream=True)
        pipe = client.pipeline()
        for event in ignored + list(selected.values()):
            pipe.xadd(stream, {"envelope": json.dumps(event)})
        pipe.xadd(stream, {"envelope": "{"})
        pipe.execute()
        subscriber._process_results(client.xreadgroup(group, "reader", {stream: ">"}, count=107), group, "reader", handle)
        assert set(calls) == {event["eventId"] for event in selected.values()}
        assert client.xpending(stream, group)["pending"] == 1
        assert client.xlen(stream + ":dlq") == 1
        pending = client.xpending(stream, group)["min"]
        client.execute_command("XCLAIM", stream, group, "reader", 0, pending, "IDLE", 60001, "JUSTID")
        subscriber._recover_idle_pending([stream], group, "reader", handle)
        assert calls.count(retry_id) == 2
        assert client.xpending(stream, group)["pending"] == 0
    finally:
        client.delete(stream, stream + ":dlq")
        client.close()
