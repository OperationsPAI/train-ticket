import unittest
from datetime import datetime, timezone
from fastapi.testclient import TestClient

from trip_planning import create_app, health, profile
from trip_planning.api import configure_runtime_endpoints
from trip_planning.events import (
    EventEnvelope,
    FatalHandlerError,
    PublishFailed,
    TransientHandlerError,
    build_itinerary_proposed_event,
)
from trip_planning.adapters.messaging.fake import FakeEventPublisher, FakeEventSubscriber


CAPTURED = datetime(2026, 7, 3, 12, 0, tzinfo=timezone.utc)


class SkeletonTest(unittest.TestCase):
    def test_profile_matches_domain(self) -> None:
        service_profile = profile()
        self.assertEqual(service_profile["service_id"], 'trip-planning')
        self.assertEqual(service_profile["domain"], 'Trip Planning')
        self.assertEqual(health(), "ok")

    def test_fastapi_runtime_routes_are_registered(self) -> None:
        app = create_app()
        routes = {route.path for route in app.routes}
        self.assertIn("/health", routes)
        self.assertIn("/live", routes)
        self.assertIn("/ready", routes)
        self.assertIn("/metadata", routes)

    def test_request_and_correlation_ids_are_propagated(self) -> None:
        client = TestClient(create_app())
        response = client.get(
            "/health",
            headers={"X-Request-Id": "req-123", "X-Correlation-Id": "corr-456"},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["X-Request-Id"], "req-123")
        self.assertEqual(response.headers["X-Correlation-Id"], "corr-456")

    def test_request_and_correlation_ids_are_generated(self) -> None:
        client = TestClient(create_app())
        response = client.get("/live")
        self.assertEqual(response.status_code, 200)
        request_id = response.headers["X-Request-Id"]
        self.assertTrue(request_id)
        self.assertEqual(response.headers["X-Correlation-Id"], request_id)

    def test_observability_trace_hook_is_opt_in(self) -> None:
        events: list[tuple[str, dict[str, object]]] = []

        def tracer(event: str, attributes: dict[str, object]) -> None:
            events.append((event, attributes))

        client = TestClient(create_app(tracer=tracer))
        response = client.get("/metadata", headers={"X-Request-Id": "req-trace"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual([event for event, _ in events], ["http.request.start", "http.request.complete"])
        self.assertEqual(events[0][1]["request_id"], "req-trace")
        self.assertEqual(events[1][1]["status_code"], 200)


class HealthzReadyzTest(unittest.TestCase):
    def test_healthz_endpoint(self) -> None:
        client = TestClient(create_app())
        response = client.get("/healthz")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["status"], "ok")
        self.assertIn("service", data)

    def test_readyz_endpoint(self) -> None:
        client = TestClient(create_app())
        response = client.get("/readyz")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "ok")


class ApiV1Test(unittest.TestCase):
    def test_api_v1_search_route_registered(self) -> None:
        app = create_app()
        routes = {route.path for route in app.routes}
        self.assertIn("/api/v1/itineraries/search", routes)

    def test_api_v1_get_itinerary_route_registered(self) -> None:
        app = create_app()
        routes = {route.path for route in app.routes}
        self.assertIn("/api/v1/itineraries/{itineraryRef}", routes)


    def test_api_v1_search_happy_path_contract_shape(self) -> None:
        client = TestClient(create_app())
        response = client.post(
            "/api/v1/itineraries/search",
            json={
                "originRef": "station:A",
                "destinationRef": "station:B",
                "departureDate": "2026-08-01",
                "travelerRefs": ["tvl-1"],
                "channel": "WEB",
                "maxResults": 5,
            },
            headers={"X-Correlation-Id": "corr-happy"},
        )
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertIn("intentRef", body)
        self.assertIn("itineraries", body)
        self.assertIn("planningSnapshotRefs", body)
        self.assertEqual(body["itineraries"][0]["priceHint"], {"currency": "CNY", "minorUnits": 0})
        self.assertIn("X-Correlation-Id", response.headers)

    def test_api_v1_get_itinerary_happy_path_after_search(self) -> None:
        client = TestClient(create_app())
        search = client.post(
            "/api/v1/itineraries/search",
            json={
                "originRef": "station:A",
                "destinationRef": "station:B",
                "departureDate": "2026-08-01",
                "travelerRefs": ["tvl-1"],
                "channel": "WEB",
            },
        )
        itinerary_ref = search.json()["itineraries"][0]["itineraryRef"]
        response = client.get(f"/api/v1/itineraries/{itinerary_ref}")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["itineraryRef"], itinerary_ref)

    def test_api_v1_search_validation_failure(self) -> None:
        client = TestClient(create_app())
        # Missing required fields
        response = client.post(
            "/api/v1/itineraries/search",
            json={"maxResults": 5},
            headers={"X-Correlation-Id": "corr-test-1"},
        )
        self.assertEqual(response.status_code, 400)
        body = response.json()
        self.assertEqual(body["code"], "VALIDATION_FAILED")
        self.assertIn("correlationId", body)
        self.assertIn("message", body)
        self.assertIn("details", body)

    def test_api_v1_search_validation_failure_body_shape(self) -> None:
        client = TestClient(create_app())
        response = client.post(
            "/api/v1/itineraries/search",
            json={"invalid": True},
            headers={"X-Correlation-Id": "corr-test-2"},
        )
        self.assertEqual(response.status_code, 400)
        body = response.json()
        # Check canonical error body shape
        self.assertIn("code", body)
        self.assertIn("message", body)
        self.assertIn("correlationId", body)
        self.assertIn("details", body)
        self.assertEqual(body["code"], "VALIDATION_FAILED")

    def test_get_itinerary_not_found(self) -> None:
        client = TestClient(create_app())
        response = client.get(
            "/api/v1/itineraries/itin_nonexistent",
            headers={"X-Correlation-Id": "corr-test-3"},
        )
        self.assertEqual(response.status_code, 404)
        body = response.json()
        self.assertEqual(body["code"], "NOT_FOUND")
        self.assertIn("itin_nonexistent", body["message"])

    def test_get_itinerary_canonical_error_shape(self) -> None:
        client = TestClient(create_app())
        response = client.get("/api/v1/itineraries/itin_abc123")
        body = response.json()
        self.assertIn("code", body)
        self.assertIn("message", body)
        self.assertIn("correlationId", body)
        self.assertIn("details", body)
        self.assertEqual(body["code"], "NOT_FOUND")
        self.assertEqual(body["details"], {})


class IdempotencyTest(unittest.TestCase):

    def test_idempotent_replay_returns_original_result(self) -> None:
        client = TestClient(create_app())
        payload = {
            "originRef": "station:A",
            "destinationRef": "station:B",
            "departureDate": "2026-08-01",
            "travelerRefs": ["tvl-1"],
            "channel": "WEB",
        }
        headers = {"Idempotency-Key": "018f0000-0000-7000-8000-000000000001"}
        first = client.post("/api/v1/itineraries/search", json=payload, headers=headers)
        replay = client.post("/api/v1/itineraries/search", json=payload, headers=headers)
        self.assertEqual(first.status_code, 200)
        self.assertEqual(replay.status_code, 200)
        self.assertEqual(replay.json(), first.json())

    def test_idempotency_key_reused_with_different_body_is_rejected(self) -> None:
        client = TestClient(create_app())
        payload = {
            "originRef": "station:A",
            "destinationRef": "station:B",
            "departureDate": "2026-08-01",
            "travelerRefs": ["tvl-1"],
            "channel": "WEB",
        }
        headers = {"Idempotency-Key": "018f0000-0000-7000-8000-000000000002"}
        self.assertEqual(client.post("/api/v1/itineraries/search", json=payload, headers=headers).status_code, 200)
        changed = dict(payload, destinationRef="station:C")
        response = client.post("/api/v1/itineraries/search", json=changed, headers=headers)
        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "IDEMPOTENCY_KEY_REUSED")

    def test_search_is_query_no_idempotency_required(self) -> None:
        """Search is a query endpoint; idempotency key is not required per contract."""
        client = TestClient(create_app())
        response = client.post(
            "/api/v1/itineraries/search",
            json={
                "originRef": "station:A",
                "destinationRef": "station:B",
                "departureDate": "2026-08-01",
                "travelerRefs": ["tvl-1"],
                "channel": "web",
            },
        )
        # Should not reject for missing idempotency key
        self.assertNotEqual(response.status_code, 422)


class EventEnvelopeTest(unittest.TestCase):
    def test_envelope_round_trip(self) -> None:
        envelope = EventEnvelope(
            eventId="evt-test-1",
            eventType="ItineraryProposed",
            producer="trip-planning",
            causationId="cmd-test-1",
            correlationId="corr-test-1",
            occurredAt=CAPTURED,
            payload={"intentRef": "intent-test", "itineraries": []},
        )
        json_dict = envelope.to_json_dict()
        restored = EventEnvelope.from_json_dict(json_dict)
        self.assertEqual(restored.eventId, envelope.eventId)
        self.assertEqual(restored.eventType, envelope.eventType)
        self.assertEqual(restored.producer, envelope.producer)
        self.assertEqual(restored.causationId, envelope.causationId)
        self.assertEqual(restored.correlationId, envelope.correlationId)
        self.assertEqual(restored.payload["intentRef"], "intent-test")

    def test_envelope_has_correct_id_prefix(self) -> None:
        envelope = EventEnvelope(
            eventId="evt-test-prefix",
            eventType="TestEvent",
            payload={},
        )
        self.assertTrue(envelope.eventId.startswith("evt-"))

    def test_envelope_json_serialization(self) -> None:
        envelope = EventEnvelope(
            eventId="evt-test-json",
            eventType="ItineraryProposed",
            payload={"key": "value"},
        )
        json_dict = envelope.to_json_dict()
        self.assertEqual(json_dict["eventId"], "evt-test-json")
        self.assertEqual(json_dict["eventType"], "ItineraryProposed")
        self.assertEqual(json_dict["payload"]["key"], "value")
        self.assertEqual(json_dict["producer"], "trip-planning")
        self.assertEqual(json_dict["schemaVersion"], 1)


class EventPublisherTest(unittest.TestCase):

    def test_search_publishes_itinerary_proposed_event(self) -> None:
        publisher = FakeEventPublisher()
        client = TestClient(create_app(event_publisher=publisher))
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
        subscriber.simulate_message(envelope)  # duplicate

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


class EventEnvelopeWireFormatTest(unittest.TestCase):
    def test_envelope_to_json_uses_camel_case(self) -> None:
        envelope = EventEnvelope(
            eventId="evt-wire-1",
            eventType="ItineraryProposed",
            producer="trip-planning",
            causationId="cmd-wire",
            correlationId="corr-wire",
            occurredAt=CAPTURED,
            payload={"intentRef": "intent-wire", "itineraries": []},
        )
        json_dict = envelope.to_json_dict()
        # camelCase per contract
        self.assertIn("eventId", json_dict)
        self.assertIn("eventType", json_dict)
        self.assertIn("schemaVersion", json_dict)
        self.assertIn("occurredAt", json_dict)
        self.assertIn("correlationId", json_dict)
        self.assertIn("causationId", json_dict)
        self.assertIn("producer", json_dict)
        self.assertIn("payload", json_dict)

    def test_envelope_occurred_at_is_rfc3339(self) -> None:
        envelope = EventEnvelope(
            eventId="evt-rfc-1",
            eventType="Test",
            occurredAt=datetime(2026, 7, 3, 10, 30, 0, 123000, tzinfo=timezone.utc),
            payload={},
        )
        json_dict = envelope.to_json_dict()
        self.assertEqual(json_dict["occurredAt"], "2026-07-03T10:30:00.123Z")


if __name__ == "__main__":
    unittest.main()
