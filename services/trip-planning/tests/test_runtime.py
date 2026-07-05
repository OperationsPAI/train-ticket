import unittest
from uuid import UUID

from fastapi.testclient import TestClient

from trip_planning import create_app, health, profile
from trip_planning.adapters.messaging.fake import FakeEventPublisher


class SkeletonTest(unittest.TestCase):
    def test_profile_matches_domain(self) -> None:
        service_profile = profile()
        self.assertEqual(service_profile["service_id"], 'trip-planning')
        self.assertEqual(service_profile["domain"], 'Trip Planning')
        self.assertEqual(health(), "ok")

    def test_fastapi_runtime_routes_are_registered(self) -> None:
        app = create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False)
        routes = {route.path for route in app.routes}
        self.assertIn("/health", routes)
        self.assertIn("/live", routes)
        self.assertIn("/ready", routes)
        self.assertIn("/metadata", routes)

    def test_request_and_correlation_ids_are_propagated(self) -> None:
        client = TestClient(create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False))
        response = client.get(
            "/health",
            headers={"X-Request-Id": "req-123", "X-Correlation-Id": "corr-456"},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["X-Request-Id"], "req-123")
        self.assertEqual(response.headers["X-Correlation-Id"], "corr-456")

    def test_request_and_correlation_ids_are_generated(self) -> None:
        client = TestClient(create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False))
        response = client.get("/live")
        self.assertEqual(response.status_code, 200)
        request_id = response.headers["X-Request-Id"]
        correlation_id = response.headers["X-Correlation-Id"]
        self.assertEqual(UUID(request_id).version, 7)
        self.assertEqual(UUID(correlation_id).version, 7)

    def test_observability_trace_hook_is_opt_in(self) -> None:
        events: list[tuple[str, dict[str, object]]] = []

        def tracer(event: str, attributes: dict[str, object]) -> None:
            events.append((event, attributes))

        client = TestClient(create_app(tracer=tracer, event_publisher=FakeEventPublisher(), start_event_subscriber=False))
        response = client.get("/metadata", headers={"X-Request-Id": "req-trace"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual([event for event, _ in events], ["http.request.start", "http.request.complete"])
        self.assertEqual(events[0][1]["request_id"], "req-trace")
        self.assertEqual(events[1][1]["status_code"], 200)


class HealthzReadyzTest(unittest.TestCase):
    def test_healthz_endpoint(self) -> None:
        client = TestClient(create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False))
        response = client.get("/healthz")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["status"], "ok")
        self.assertIn("service", data)

    def test_readyz_endpoint(self) -> None:
        client = TestClient(create_app(event_publisher=FakeEventPublisher(), start_event_subscriber=False))
        response = client.get("/readyz")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "ok")
