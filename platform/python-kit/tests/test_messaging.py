import json

from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.messaging import MAX_DELIVERY_ATTEMPTS, RedisEventSubscriber, dlq_for_stream


class FakeRedis:
    def __init__(self) -> None:
        self.acks: list[tuple[str, str, str]] = []
        self.dlq_entries: list[tuple[str, dict[str, str]]] = []
        self.delivery_count = MAX_DELIVERY_ATTEMPTS

    def xpending_range(self, stream: str, group: str, min: str, max: str, count: int) -> list[dict[str, int]]:
        return [{"times_delivered": self.delivery_count}]

    def xadd(self, stream: str, fields: dict[str, str], maxlen: int, approximate: bool) -> None:
        self.dlq_entries.append((stream, fields))

    def xack(self, stream: str, group: str, msg_id: str) -> None:
        self.acks.append((stream, group, msg_id))


def test_unexpected_handler_exception_uses_dlq_policy_without_escaping() -> None:
    subscriber = object.__new__(RedisEventSubscriber)
    subscriber._client = FakeRedis()
    subscriber._dedup = set()
    subscriber._dedup_lock = None
    envelope = EventEnvelope(eventType="SomethingHappened", producer="tester", payload={"x": 1})
    fields = {"envelope": json.dumps(envelope.to_json_dict())}

    def handler(_: EventEnvelope) -> None:
        raise ValueError("poison message")

    subscriber._process_message("events:tester", "tester", "1-0", fields, handler)

    assert subscriber._client.acks == [("events:tester", "tester", "1-0")]
    assert subscriber._client.dlq_entries[0][0] == dlq_for_stream("events:tester")
