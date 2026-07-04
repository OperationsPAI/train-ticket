import unittest

from fastapi.testclient import TestClient

from fare_pricing import create_app, health, profile


class SkeletonTest(unittest.TestCase):
    def test_profile_matches_domain(self) -> None:
        service_profile = profile()
        self.assertEqual(service_profile["service_id"], 'fare-pricing')
        self.assertEqual(service_profile["domain"], 'Fare & Pricing')
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
            headers={"X-Request-ID": "req-123", "X-Correlation-ID": "corr-456"},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["X-Request-ID"], "req-123")
        self.assertEqual(response.headers["X-Correlation-ID"], "corr-456")

    def test_request_and_correlation_ids_are_generated(self) -> None:
        client = TestClient(create_app())
        response = client.get("/live")
        self.assertEqual(response.status_code, 200)
        request_id = response.headers["X-Request-ID"]
        self.assertTrue(request_id)
        self.assertEqual(response.headers["X-Correlation-ID"], request_id)

    def test_observability_trace_hook_is_opt_in(self) -> None:
        events: list[tuple[str, dict[str, object]]] = []

        def tracer(event: str, attributes: dict[str, object]) -> None:
            events.append((event, attributes))

        client = TestClient(create_app(tracer=tracer))
        response = client.get("/metadata", headers={"X-Request-ID": "req-trace"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual([event for event, _ in events], ["http.request.start", "http.request.complete"])
        self.assertEqual(events[0][1]["request_id"], "req-trace")
        self.assertEqual(events[1][1]["status_code"], 200)


if __name__ == "__main__":
    unittest.main()
