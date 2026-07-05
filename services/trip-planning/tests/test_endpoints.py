import unittest

from fastapi.testclient import TestClient

from trip_planning import create_app
from trip_planning.adapters.messaging.fake import FakeEventPublisher


class ApiV1Test(unittest.TestCase):
    def test_api_v1_search_route_registered(self) -> None:
        app = create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False)
        routes = {route.path for route in app.routes}
        self.assertIn("/api/v1/itineraries/search", routes)

    def test_api_v1_get_itinerary_route_registered(self) -> None:
        app = create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False)
        routes = {route.path for route in app.routes}
        self.assertIn("/api/v1/itineraries/{itineraryRef}", routes)


    def test_api_v1_search_happy_path_contract_shape(self) -> None:
        client = TestClient(create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False))
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
        client = TestClient(create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False))
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
        client = TestClient(create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False))
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
        client = TestClient(create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False))
        response = client.post(
            "/api/v1/itineraries/search",
            json={"invalid": True},
            headers={"X-Correlation-Id": "corr-test-2"},
        )
        self.assertEqual(response.status_code, 400)
        body = response.json()
        self.assertIn("code", body)
        self.assertIn("message", body)
        self.assertIn("correlationId", body)
        self.assertIn("details", body)
        self.assertEqual(body["code"], "VALIDATION_FAILED")

    def test_get_itinerary_not_found(self) -> None:
        client = TestClient(create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False))
        response = client.get(
            "/api/v1/itineraries/itin_nonexistent",
            headers={"X-Correlation-Id": "corr-test-3"},
        )
        self.assertEqual(response.status_code, 404)
        body = response.json()
        self.assertEqual(body["code"], "NOT_FOUND")
        self.assertIn("itin_nonexistent", body["message"])

    def test_get_itinerary_canonical_error_shape(self) -> None:
        client = TestClient(create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False))
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
        client = TestClient(create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False))
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
        client = TestClient(create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False))
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
        client = TestClient(create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False))
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
        self.assertNotEqual(response.status_code, 422)
