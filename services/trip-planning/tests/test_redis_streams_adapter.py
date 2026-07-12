import json
import threading
import unittest

from trip_planning.adapters.messaging.redis_streams import RedisEventSubscriber
from trip_planning.events import EventEnvelope


class RedisEventSubscriberLifecycleTest(unittest.TestCase):
    def test_subscribe_polls_until_stopped_and_recovers_periodically(self) -> None:
        envelope = EventEnvelope(eventId="evt-redis-1", eventType="TestEvent", payload={})
        envelope_json = envelope.to_json_dict()

        class RedisFake:
            def __init__(self) -> None:
                self.reads = 0
                self.claims = 0
                self.acks: list[tuple[str, str, str]] = []

            def xgroup_create(self, stream: str, group: str, id: str = "$", mkstream: bool = True) -> None:
                return None

            def xautoclaim(self, *args: object, **kwargs: object) -> tuple[str, list[object]]:
                self.claims += 1
                return "0-0", []

            def xreadgroup(self, group: str, consumer: str, streams: dict[str, str], count: int, block: int) -> list[object]:
                self.reads += 1
                if self.reads == 1:
                    return [("events:place-network", [("1-0", {"envelope": json.dumps(envelope_json)})])]
                subscriber.stop()
                return []

            def xpending_range(self, *args: object, **kwargs: object) -> list[object]:
                return [{"times_delivered": 1}]

            def xack(self, stream: str, group: str, msg_id: str) -> None:
                self.acks.append((stream, group, msg_id))

            def xadd(self, *args: object, **kwargs: object) -> None:
                return None

        redis_fake = RedisFake()
        subscriber = RedisEventSubscriber(redis_client=redis_fake)
        received: list[str] = []

        subscriber.subscribe(["events:place-network"], "trip-planning", "trip-planning-test", lambda env: received.append(env.eventId))

        self.assertEqual(received, ["evt-redis-1"])
        self.assertGreaterEqual(redis_fake.reads, 2)
        self.assertGreaterEqual(redis_fake.claims, 1)
        self.assertEqual(redis_fake.acks, [("events:place-network", "trip-planning", "1-0")])

    def test_subscribe_can_be_stopped_from_another_thread(self) -> None:
        class RedisFake:
            def xgroup_create(self, *args: object, **kwargs: object) -> None:
                return None

            def xautoclaim(self, *args: object, **kwargs: object) -> tuple[str, list[object]]:
                return "0-0", []

            def xreadgroup(self, *args: object, **kwargs: object) -> list[object]:
                return []

        subscriber = RedisEventSubscriber(redis_client=RedisFake())
        thread = threading.Thread(
            target=subscriber.subscribe,
            args=(["events:place-network"], "trip-planning", "trip-planning-test", lambda env: None),
        )
        thread.start()
        subscriber.stop()
        thread.join(timeout=1)
        self.assertFalse(thread.is_alive())
