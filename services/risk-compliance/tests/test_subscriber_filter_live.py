import json
import os
from pathlib import Path
from uuid import uuid4

import pytest
import redis

from risk_compliance.adapters.messaging.redis_streams import RedisEventSubscriber
from train_ticket_platform.messaging import TransientHandlerError


@pytest.mark.skipif(not os.environ.get("TEST_REDIS_URL") or not os.environ.get("TEST_RISK_FILTER_EVENTS"), reason="requires real Redis and recorded events")
def test_filtered_messages_preserve_retries_and_malformed_envelopes():
    events = json.loads(Path(os.environ["TEST_RISK_FILTER_EVENTS"]).read_text())
    client = redis.Redis.from_url(os.environ["TEST_REDIS_URL"], decode_responses=True)
    subscriber = RedisEventSubscriber(redis_client=client)
    stream = "test:risk-filter:" + uuid4().hex
    group = "risk-filter"
    required = next(event for event in events if event["eventType"] == "JourneyOrderCreated")
    ignored = [event for event in events if subscriber._ignored_entry({"envelope":json.dumps(event)})][:100]
    assert len(ignored) == 100
    calls = []
    def handle(envelope):
        calls.append(envelope.eventId)
        if len(calls) == 1:
            raise TransientHandlerError("dependency unavailable")
    try:
        client.xgroup_create(stream, group, "0", mkstream=True)
        pipe = client.pipeline()
        for event in ignored + [required]:
            pipe.xadd(stream, {"envelope":json.dumps(event)})
        pipe.xadd(stream, {"envelope":"{"})
        pipe.execute()
        entries = client.xreadgroup(group,"reader",{stream:">"},count=102)
        subscriber._process_results(entries,group,"reader",handle)
        assert calls == [required["eventId"]]
        assert client.xpending(stream,group)["pending"] == 1
        assert client.xlen(stream+":dlq") == 1
        entry_id = client.xpending(stream,group)["min"]
        client.execute_command("XCLAIM",stream,group,"reader",0,entry_id,"IDLE",60001,"JUSTID")
        subscriber._recover_idle_pending([stream],group,"reader",handle)
        assert calls == [required["eventId"],required["eventId"]]
        assert client.xpending(stream,group)["pending"] == 0
    finally:
        client.delete(stream,stream+":dlq")
        client.close()
