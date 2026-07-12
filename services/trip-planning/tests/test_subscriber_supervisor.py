import time
import unittest

import trip_planning.adapters.messaging.redis_streams as redis_streams
from trip_planning.adapters.messaging.redis_streams import start_trip_planning_subscription
from trip_planning.events import EventEnvelope


class SubscriberSupervisorTest(unittest.TestCase):
    def test_restarts_subscriber_after_unexpected_exit(self) -> None:
        original_delay = redis_streams.SUBSCRIBER_RESTART_DELAY_SECONDS
        redis_streams.SUBSCRIBER_RESTART_DELAY_SECONDS = 0.01

        class FlakySubscriber:
            def __init__(self) -> None:
                self.calls = 0
                self.stopped = False

            def subscribe(self, streams, group, consumer_name, handler) -> None:  # type: ignore[no-untyped-def]
                self.calls += 1
                if self.calls == 1:
                    raise RuntimeError("boom")
                handler(EventEnvelope(eventId="evt-after-restart", eventType="ServicePlanPublished", producer="service-plan", payload={"scheduledServiceRef": "ss-1"}))

        subscriber = FlakySubscriber()
        received: list[str] = []
        try:
            thread = start_trip_planning_subscription(subscriber, lambda envelope: received.append(envelope.eventId))
            deadline = time.time() + 1
            while thread.is_alive() and time.time() < deadline:
                time.sleep(0.01)
        finally:
            redis_streams.SUBSCRIBER_RESTART_DELAY_SECONDS = original_delay

        self.assertFalse(thread.is_alive())
        self.assertEqual(subscriber.calls, 2)
        self.assertEqual(received, ["evt-after-restart"])


if __name__ == "__main__":
    unittest.main()
