import unittest

from fastapi.testclient import TestClient

from trip_planning import create_app
from trip_planning.adapters.messaging.fake import FakeEventPublisher, FakeEventSubscriber
from trip_planning.events import EventEnvelope


class ProductionMessagingWiringTest(unittest.TestCase):
    def test_create_app_defaults_to_redis_publisher_from_env(self) -> None:
        import trip_planning.api as api

        created: list[bool] = []

        class PublisherFake:
            def publish(self, envelope: EventEnvelope) -> None:
                return None

        original = api._default_publisher
        try:
            api._default_publisher = lambda: (created.append(True) or PublisherFake())  # type: ignore[assignment]
            app = create_app(start_event_subscriber=False)
            self.assertTrue(created)
            self.assertIn("/api/v1/itineraries/search", {route.path for route in app.routes})
        finally:
            api._default_publisher = original  # type: ignore[assignment]

    def test_lifespan_starts_and_stops_injected_subscriber(self) -> None:
        publisher = FakeEventPublisher()
        subscriber = FakeEventSubscriber()
        app = create_app(event_publisher=publisher, event_subscriber=subscriber)
        with TestClient(app):
            self.assertEqual(len(subscriber.handlers), 1)
            streams, group, consumer_name, _handler = subscriber.handlers[0]
            self.assertEqual(streams, ["events:place-network", "events:service-plan", "events:capacity-availability"])
            self.assertEqual(group, "trip-planning")
            self.assertTrue(consumer_name.startswith("trip-planning-"))
            event = EventEnvelope(eventId="evt-upstream", eventType="PlaceUpdated", producer="place-network", payload={"placeRef": "station:A"})
            subscriber.simulate_message(event)
            self.assertEqual(app.state.consumed_events["evt-upstream"]["eventType"], "PlaceUpdated")
            self.assertEqual(app.state.upstream_event_payloads["evt-upstream"], {"placeRef": "station:A"})

    def test_lifespan_defaults_to_redis_subscriber(self) -> None:
        import trip_planning.api as api

        class SubscriberFake(FakeEventSubscriber):
            pass

        publisher = FakeEventPublisher()
        subscriber = SubscriberFake()
        original = api._default_subscriber
        try:
            api._default_subscriber = lambda: subscriber  # type: ignore[assignment]
            app = create_app(event_publisher=publisher)
            with TestClient(app):
                self.assertEqual(len(subscriber.handlers), 1)
        finally:
            api._default_subscriber = original  # type: ignore[assignment]
