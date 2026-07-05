import unittest

from fastapi.testclient import TestClient

from risk_compliance import (
    Challenge,
    ChallengeOutcome,
    ChallengeType,
    Decision,
    EvidenceSummary,
    RiskAssessment,
    RiskComplianceError,
    RiskDecision,
    RiskLevel,
    allow_subject,
    assess_risk,
    block_subject,
    create_app,
    health,
    issue_challenge,
    profile,
    record_evidence,
    resolve_challenge,
)
from risk_compliance.application import is_uuid7, uuid7


class SkeletonTest(unittest.TestCase):
    def test_profile_matches_domain(self) -> None:
        service_profile = profile()
        self.assertEqual(service_profile["service_id"], 'risk-compliance')
        self.assertEqual(service_profile["domain"], 'Risk & Compliance')
        self.assertIn('REQ-020', service_profile["work_packages"])
        self.assertIn('RiskAssessment', service_profile["owns"])
        self.assertIn('Challenge', service_profile["owns"])
        self.assertIn('RiskDecision', service_profile["owns"])
        self.assertIn('EvidenceSummary', service_profile["owns"])
        self.assertEqual(health(), "ok")

    def test_domain_types_are_importable(self) -> None:
        self.assertIsNotNone(RiskAssessment)
        self.assertIsNotNone(Challenge)
        self.assertIsNotNone(RiskDecision)
        self.assertIsNotNone(EvidenceSummary)
        self.assertIsNotNone(RiskComplianceError)
        self.assertIsNotNone(Decision)
        self.assertIsNotNone(RiskLevel)
        self.assertIsNotNone(ChallengeType)
        self.assertIsNotNone(ChallengeOutcome)

    def test_domain_commands_are_importable(self) -> None:
        self.assertIsNotNone(assess_risk)
        self.assertIsNotNone(issue_challenge)
        self.assertIsNotNone(resolve_challenge)
        self.assertIsNotNone(block_subject)
        self.assertIsNotNone(allow_subject)
        self.assertIsNotNone(record_evidence)

    def test_fastapi_runtime_routes_are_registered(self) -> None:
        app = create_app()
        routes = {route.path for route in app.routes}
        self.assertIn("/health", routes)
        self.assertIn("/live", routes)
        self.assertIn("/ready", routes)
        self.assertIn("/metadata", routes)

    def test_request_and_correlation_ids_are_propagated(self) -> None:
        client = TestClient(create_app())
        correlation_id = str(uuid7())
        response = client.get(
            "/health",
            headers={"X-Request-ID": "req-123", "X-Correlation-ID": correlation_id},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["X-Request-ID"], "req-123")
        self.assertEqual(response.headers["X-Correlation-ID"], correlation_id)

    def test_request_and_correlation_ids_are_generated(self) -> None:
        client = TestClient(create_app())
        response = client.get("/live")
        self.assertEqual(response.status_code, 200)
        request_id = response.headers["X-Request-ID"]
        self.assertTrue(request_id)
        self.assertTrue(is_uuid7(request_id))
        self.assertTrue(is_uuid7(response.headers["X-Correlation-ID"]))

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
