from __future__ import annotations

from fastapi.testclient import TestClient

from risk_compliance import (
    EventEnvelope,
    InMemoryAssessmentRepository,
    InMemoryEventPublisher,
    InMemoryEventSubscriber,
    PublishFailed,
    RiskComplianceService,
    create_app,
)


def fake_app():
    return create_app(service=RiskComplianceService(InMemoryEventPublisher(), InMemoryAssessmentRepository()))


def test_assess_risk_happy_path_and_get() -> None:
    app = fake_app()
    client = TestClient(app)

    response = client.post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c333", "X-Correlation-Id": "corr-test"},
        json={"subjectRef": "ord-123", "scenario": "order_risk", "context": {"riskScore": 120}},
    )

    assert response.status_code == 201
    body = response.json()
    assert body == {
        "assessmentId": body["assessmentId"],
        "subjectRef": "ord-123",
        "scenario": "order_risk",
        "decision": "ALLOW",
        "score": 120,
        "level": "LOW",
        "policyVersion": "1.0.0",
        "reasonCode": "PASSED_RISK_CHECKS",
        "reasonExplanation": "All configured risk checks passed",
        "assessedAt": body["assessedAt"],
    }
    assert body["assessmentId"].startswith("asmt-")
    assert body["assessedAt"].endswith("Z")

    fetched = client.get(f"/api/v1/risk-assessments/{body['assessmentId']}")
    assert fetched.status_code == 200
    assert fetched.json() == body


def test_get_unknown_assessment_returns_not_found_error_body() -> None:
    response = TestClient(fake_app()).get("/api/v1/risk-assessments/asmt-missing", headers={"X-Correlation-Id": "corr-404"})

    assert response.status_code == 404
    assert response.json() == {
        "code": "NOT_FOUND",
        "message": "Risk assessment was not found",
        "correlationId": "corr-404",
        "details": {},
    }


def test_validation_failure_uses_canonical_400_body() -> None:
    response = TestClient(fake_app()).post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c334", "X-Correlation-Id": "corr-validation"},
        json={"subjectRef": "ord-123", "scenario": "invalid", "context": {}},
    )

    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "VALIDATION_FAILED"
    assert body["message"] == "Request validation failed"
    assert body["correlationId"] == "corr-validation"
    assert "errors" in body["details"]


def test_missing_idempotency_key_is_validation_error() -> None:
    response = TestClient(fake_app()).post(
        "/api/v1/risk-assessments",
        headers={"X-Correlation-Id": "corr-idem"},
        json={"subjectRef": "ord-123", "scenario": "order_risk", "context": {}},
    )

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_FAILED"


class FailingPublisher(InMemoryEventPublisher):
    def publish(self, envelope: EventEnvelope) -> None:
        raise PublishFailed("downstream unavailable")


def test_malformed_idempotency_key_is_validation_error() -> None:
    response = TestClient(fake_app()).post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": "not-a-uuid-v7", "X-Correlation-Id": "corr-idem-format"},
        json={"subjectRef": "ord-123", "scenario": "order_risk", "context": {}},
    )

    assert response.status_code == 400
    assert response.json() == {
        "code": "VALIDATION_FAILED",
        "message": "Idempotency-Key must be a UUID v7",
        "correlationId": "corr-idem-format",
        "details": {},
    }


def test_publish_failure_returns_unavailable_body_after_save() -> None:
    repository = InMemoryAssessmentRepository()
    app = create_app(service=RiskComplianceService(FailingPublisher(), repository))

    response = TestClient(app).post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c338", "X-Correlation-Id": "corr-publish"},
        json={"subjectRef": "ord-123", "scenario": "order_risk", "context": {"riskScore": 120}},
    )

    assert response.status_code == 503
    assert response.json()["code"] == "UNAVAILABLE"
    assert response.json()["details"] == {"deliverySemantics": "AT_LEAST_ONCE"}
    assert len(repository._assessments) == 1


def test_idempotent_replay_returns_original_result() -> None:
    client = TestClient(fake_app())
    headers = {"Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c335", "X-Correlation-Id": "corr-replay"}
    payload = {"subjectRef": "pi-123", "scenario": "payment_risk", "context": {"riskScore": 910}}

    created = client.post("/api/v1/risk-assessments", headers=headers, json=payload)
    replayed = client.post("/api/v1/risk-assessments", headers=headers, json=payload)

    assert created.status_code == 201
    assert replayed.status_code == 201
    assert replayed.json() == created.json()


def test_idempotency_key_reused_with_different_body_is_rejected() -> None:
    client = TestClient(fake_app())
    headers = {"Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c336", "X-Correlation-Id": "corr-reused"}
    first = {"subjectRef": "ord-123", "scenario": "order_risk", "context": {"riskScore": 10}}
    second = {"subjectRef": "ord-456", "scenario": "order_risk", "context": {"riskScore": 10}}

    assert client.post("/api/v1/risk-assessments", headers=headers, json=first).status_code == 201
    response = client.post("/api/v1/risk-assessments", headers=headers, json=second)

    assert response.status_code == 422
    assert response.json()["code"] == "IDEMPOTENCY_KEY_REUSED"


def test_publisher_wraps_domain_event_in_contract_envelope() -> None:
    app = fake_app()
    client = TestClient(app)

    response = client.post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c337", "X-Correlation-Id": "corr-envelope"},
        json={"subjectRef": "acct-123", "scenario": "account_risk", "context": {"riskScore": 650}},
    )

    assert response.status_code == 201
    [envelope] = app.state.publisher.envelopes
    assert envelope.eventId.startswith("evt-")
    assert envelope.eventId.split("-", 1)[1][14] == "7"
    assert response.json()["assessmentId"].split("-", 1)[1][14] == "7"
    assert envelope.eventType == "RiskAssessmentResult"
    assert envelope.producer == "risk-compliance"
    assert envelope.schemaVersion == 1
    assert envelope.correlationId == "corr-envelope"
    assert envelope.causationId.startswith("cmd-")
    assert envelope.causationId.split("-", 1)[1][14] == "7"
    assert envelope.occurredAt == response.json()["assessedAt"]
    assert envelope.payload == response.json()


def test_subscriber_deduplicates_duplicate_event_id() -> None:
    envelope = EventEnvelope(
        eventId="evt-duplicate",
        eventType="RiskAssessmentResult",
        occurredAt="2026-07-05T10:30:00.000Z",
        correlationId="corr-duplicate",
        causationId="cmd-duplicate",
        producer="risk-compliance",
        schemaVersion=1,
        payload={"assessmentId": "asmt-1"},
    )
    subscriber = InMemoryEventSubscriber([envelope, envelope])
    handled: list[str] = []

    subscriber.subscribe(["unused"], "risk-compliance", "risk-compliance-test", lambda event: handled.append(event.eventId))

    assert handled == ["evt-duplicate"]



def test_envelope_accepts_optional_causation_id() -> None:
    envelope = EventEnvelope.from_mapping(
        {
            "eventId": "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c339",
            "eventType": "PaymentCaptured",
            "occurredAt": "2026-07-05T10:30:00.000Z",
            "correlationId": "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c340",
            "producer": "payment",
            "schemaVersion": 1,
            "payload": {"paymentIntentId": "pi-123"},
        }
    )

    assert envelope.causationId is None
    assert "causationId" not in envelope.to_dict()


def test_risk_score_zero_is_respected_when_score_fallback_present() -> None:
    response = TestClient(fake_app()).post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c341", "X-Correlation-Id": "corr-zero"},
        json={"subjectRef": "ord-123", "scenario": "order_risk", "context": {"riskScore": 0, "score": 999}},
    )

    assert response.status_code == 201
    assert response.json()["score"] == 0
    assert response.json()["decision"] == "ALLOW"
