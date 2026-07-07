import unittest
from datetime import datetime, timezone

from fastapi.testclient import TestClient

from trip_planning import create_app
from trip_planning.adapters.messaging.fake import FakeEventPublisher, FakeEventSubscriber
from trip_planning.events import EventEnvelope, PublishFailed


CAPTURED = datetime(2026, 7, 3, 12, 0, tzinfo=timezone.utc)


class EventPublisherTest(unittest.TestCase):

    def test_search_publishes_itinerary_proposed_event(self) -> None:
        publisher = FakeEventPublisher()
        client = TestClient(create_app(event_publisher=publisher, start_event_subscriber=False))
        response = client.post(
            "/api/v1/itineraries/search",
            json={
                "originRef": "station:A",
                "destinationRef": "station:B",
                "departureDate": "2026-08-01",
                "travelerRefs": ["tvl-1"],
                "channel": "WEB",
            },
            headers={"X-Correlation-Id": "corr-published"},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(publisher.published), 1)
        envelope = publisher.published[0]
        self.assertEqual(envelope.eventType, "ItineraryProposed")
        self.assertEqual(envelope.producer, "trip-planning")
        self.assertEqual(envelope.correlationId, "corr-published")
        self.assertEqual(envelope.payload["intentRef"], response.json()["intentRef"])

    def test_search_saves_itinerary_and_outbox_in_same_unit_of_work(self) -> None:
        class RecordingStore:
            def __init__(self) -> None:
                self.in_transaction = False
                self.saved_inside_transaction = False

            def transaction(self):
                store = self

                class Context:
                    def __enter__(self):
                        store.in_transaction = True
                        return self

                    def __exit__(self, exc_type, exc, traceback):
                        store.in_transaction = False

                return Context()

            def candidates(self, origin_ref: str, destination_ref: str, departure_date: str) -> list[object]:
                return []

            def save_itinerary(self, itinerary: dict[str, object]) -> None:
                self.saved_inside_transaction = self.in_transaction

            def load(self) -> None:
                return None

        class RecordingPublisher(FakeEventPublisher):
            def __init__(self, store: RecordingStore) -> None:
                super().__init__()
                self.store = store
                self.published_inside_transaction = False

            def publish(self, envelope: EventEnvelope) -> None:
                self.published_inside_transaction = self.store.in_transaction
                super().publish(envelope)

        import trip_planning.api as api

        store = RecordingStore()
        publisher = RecordingPublisher(store)
        original = api._active_plan_store
        api._active_plan_store = store
        try:
            client = TestClient(create_app(event_publisher=publisher, start_event_subscriber=False))
            api._active_plan_store = store
            response = client.post(
                "/api/v1/itineraries/search",
                json={
                    "originRef": "station:A",
                    "destinationRef": "station:B",
                    "departureDate": "2026-08-01",
                    "travelerRefs": ["tvl-1"],
                    "channel": "WEB",
                },
            )
        finally:
            api._active_plan_store = original

        self.assertEqual(response.status_code, 200)
        self.assertTrue(store.saved_inside_transaction)
        self.assertTrue(publisher.published_inside_transaction)

    def test_fake_publisher_records_events(self) -> None:
        publisher = FakeEventPublisher()
        envelope = EventEnvelope(
            eventId="evt-pub-test-1",
            eventType="ItineraryProposed",
            payload={"intentRef": "intent-1"},
        )
        publisher.publish(envelope)
        self.assertEqual(len(publisher.published), 1)
        self.assertEqual(publisher.published[0].eventId, "evt-pub-test-1")

    def test_fake_publisher_fail_on_publish(self) -> None:
        publisher = FakeEventPublisher()
        publisher.fail_on_publish = True
        envelope = EventEnvelope(
            eventId="evt-pub-fail",
            eventType="ItineraryProposed",
            payload={},
        )
        with self.assertRaises(PublishFailed):
            publisher.publish(envelope)

    def test_publisher_wraps_event_in_correct_envelope(self) -> None:
        publisher = FakeEventPublisher()
        envelope = EventEnvelope(
            eventId="evt-wrap-test",
            eventType="ItineraryProposed",
            producer="trip-planning",
            causationId="cmd-wrap",
            correlationId="corr-wrap",
            occurredAt=CAPTURED,
            payload={
                "intentRef": "intent-wrap",
                "itineraries": [
                    {
                        "itineraryRef": "itin-1",
                        "legs": [],
                        "priceHint": None,
                        "availabilityHint": None,
                    }
                ],
                "planningSnapshotRefs": [],
            },
        )
        publisher.publish(envelope)
        self.assertEqual(len(publisher.published), 1)
        published = publisher.published[0]
        self.assertEqual(published.eventType, "ItineraryProposed")
        self.assertEqual(published.producer, "trip-planning")
        self.assertEqual(published.payload["intentRef"], "intent-wrap")


class EventSubscriberTest(unittest.TestCase):
    def test_subscriber_dedup_duplicate_event_id(self) -> None:
        subscriber = FakeEventSubscriber()
        received: list[EventEnvelope] = []

        def handler(envelope: EventEnvelope) -> None:
            received.append(envelope)

        subscriber.subscribe(["events:test"], "trip-planning", "consumer-1", handler)

        envelope = EventEnvelope(
            eventId="evt-dedup-1",
            eventType="TestEvent",
            payload={},
        )
        subscriber.simulate_message(envelope)
        subscriber.simulate_message(envelope)

        self.assertEqual(len(received), 1)
        self.assertEqual(received[0].eventId, "evt-dedup-1")

    def test_subscriber_receives_messages(self) -> None:
        subscriber = FakeEventSubscriber()
        received: list[EventEnvelope] = []

        def handler(envelope: EventEnvelope) -> None:
            received.append(envelope)

        subscriber.subscribe(["events:test"], "trip-planning", "consumer-2", handler)

        env1 = EventEnvelope(eventId="evt-rec-1", eventType="TestEvent", payload={"n": 1})
        env2 = EventEnvelope(eventId="evt-rec-2", eventType="TestEvent", payload={"n": 2})
        subscriber.simulate_message(env1)
        subscriber.simulate_message(env2)

        self.assertEqual(len(received), 2)

    def test_subscriber_handler_receives_correct_envelope(self) -> None:
        subscriber = FakeEventSubscriber()
        received: list[EventEnvelope] = []

        def handler(envelope: EventEnvelope) -> None:
            received.append(envelope)

        subscriber.subscribe(["events:test"], "trip-planning", "consumer-3", handler)

        envelope = EventEnvelope(
            eventId="evt-correct",
            eventType="ItineraryProposed",
            producer="trip-planning",
            payload={"intentRef": "intent-correct"},
        )
        subscriber.simulate_message(envelope)
        self.assertEqual(len(received), 1)
        self.assertEqual(received[0].payload["intentRef"], "intent-correct")
