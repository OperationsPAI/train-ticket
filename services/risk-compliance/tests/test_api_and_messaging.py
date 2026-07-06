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
from risk_compliance.application import is_prefixed_uuid7, is_uuid7, uuid7


def fake_app():
    return create_app(service=RiskComplianceService(InMemoryEventPublisher(), InMemoryAssessmentRepository()))


def test_assess_risk_happy_path_and_get() -> None:
    app = fake_app()
    client = TestClient(app)

    response = client.post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": str(uuid7()), "X-Correlation-Id": str(uuid7())},
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


def test_injected_app_assess_risk_smoke_uses_platform_fake_publisher() -> None:
    app = fake_app()
    client = TestClient(app)

    response = client.post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": str(uuid7()), "X-Correlation-Id": str(uuid7())},
        json={"subjectRef": "ord-default", "scenario": "order_risk", "context": {"riskScore": 10}},
    )

    assert response.status_code == 201
    body = response.json()
    assert body["subjectRef"] == "ord-default"
    [envelope] = app.state.publisher.envelopes
    assert envelope.to_json_dict()["eventType"] == "RiskAssessmentResult"
    assert envelope.payload["assessmentId"] == body["assessmentId"]


def test_default_app_wires_redis_publisher_and_journey_order_subscriber() -> None:
    app = create_app()

    assert app.state.publisher.__class__.__name__ == "RedisEventPublisher"
    assert app.state.subscriber.__class__.__name__ == "RedisEventSubscriber"


def test_get_unknown_assessment_returns_not_found_error_body() -> None:
    response = TestClient(fake_app()).get("/api/v1/risk-assessments/asmt-missing", headers={"X-Correlation-Id": str(uuid7())})

    assert response.status_code == 404
    body = response.json()
    assert body["code"] == "NOT_FOUND"
    assert body["message"] == "Risk assessment was not found"
    assert body["correlationId"] == response.headers["X-Correlation-Id"]
    assert is_uuid7(body["correlationId"])
    assert body["details"] == {}


def test_validation_failure_uses_canonical_400_body() -> None:
    response = TestClient(fake_app()).post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": str(uuid7()), "X-Correlation-Id": str(uuid7())},
        json={"subjectRef": "ord-123", "scenario": "invalid", "context": {}},
    )

    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "VALIDATION_FAILED"
    assert body["message"] == "Request validation failed"
    assert is_uuid7(body["correlationId"])
    assert "errors" in body["details"]


def test_missing_idempotency_key_is_validation_error() -> None:
    response = TestClient(fake_app()).post(
        "/api/v1/risk-assessments",
        headers={"X-Correlation-Id": str(uuid7())},
        json={"subjectRef": "ord-123", "scenario": "order_risk", "context": {}},
    )

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_FAILED"


class FailingPublisher(InMemoryEventPublisher):
    def publish(self, envelope: EventEnvelope) -> None:
        raise PublishFailed("downstream unavailable")


class FlakyPublisher(InMemoryEventPublisher):
    def __init__(self) -> None:
        super().__init__()
        self.failures_remaining = 1

    def publish(self, envelope: EventEnvelope) -> None:
        if self.failures_remaining > 0:
            self.failures_remaining -= 1
            raise PublishFailed("downstream unavailable")
        super().publish(envelope)


def test_malformed_idempotency_key_is_validation_error() -> None:
    response = TestClient(fake_app()).post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": "not-a-uuid-v7", "X-Correlation-Id": str(uuid7())},
        json={"subjectRef": "ord-123", "scenario": "order_risk", "context": {}},
    )

    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "VALIDATION_FAILED"
    assert body["message"] == "Idempotency-Key must be a UUID v7"
    assert is_uuid7(body["correlationId"])
    assert body["details"] == {}


def test_publish_failure_returns_unavailable_body_after_save() -> None:
    repository = InMemoryAssessmentRepository()
    app = create_app(service=RiskComplianceService(FailingPublisher(), repository))

    response = TestClient(app).post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": str(uuid7()), "X-Correlation-Id": str(uuid7())},
        json={"subjectRef": "ord-123", "scenario": "order_risk", "context": {"riskScore": 120}},
    )

    assert response.status_code == 503
    assert response.json()["code"] == "UNAVAILABLE"
    assert response.json()["details"] == {"deliverySemantics": "AT_LEAST_ONCE"}
    assert len(repository._assessments) == 1


def test_publish_failure_retry_same_key_publishes_and_returns_created() -> None:
    repository = InMemoryAssessmentRepository()
    publisher = FlakyPublisher()
    app = create_app(service=RiskComplianceService(publisher, repository))
    client = TestClient(app)
    headers = {"Idempotency-Key": str(uuid7()), "X-Correlation-Id": str(uuid7())}
    payload = {"subjectRef": "ord-123", "scenario": "order_risk", "context": {"riskScore": 120}}

    first = client.post("/api/v1/risk-assessments", headers=headers, json=payload)
    retry = client.post("/api/v1/risk-assessments", headers=headers, json=payload)

    assert first.status_code == 503
    assert retry.status_code == 201
    assert len(repository._assessments) == 1
    assert len(publisher.envelopes) == 1
    assert retry.json() == next(iter(repository._assessments.values())).to_dict()


def test_idempotent_replay_returns_original_result() -> None:
    client = TestClient(fake_app())
    headers = {"Idempotency-Key": str(uuid7()), "X-Correlation-Id": str(uuid7())}
    payload = {"subjectRef": "pi-123", "scenario": "payment_risk", "context": {"riskScore": 910}}

    created = client.post("/api/v1/risk-assessments", headers=headers, json=payload)
    replayed = client.post("/api/v1/risk-assessments", headers=headers, json=payload)

    assert created.status_code == 201
    assert replayed.status_code == 201
    assert replayed.json() == created.json()
    assert replayed.headers["X-Request-Id"]
    assert replayed.headers["X-Correlation-Id"]


def test_idempotency_key_reused_with_different_body_is_rejected() -> None:
    client = TestClient(fake_app())
    headers = {"Idempotency-Key": str(uuid7()), "X-Correlation-Id": str(uuid7())}
    first = {"subjectRef": "ord-123", "scenario": "order_risk", "context": {"riskScore": 10}}
    second = {"subjectRef": "ord-456", "scenario": "order_risk", "context": {"riskScore": 10}}

    assert client.post("/api/v1/risk-assessments", headers=headers, json=first).status_code == 201
    response = client.post("/api/v1/risk-assessments", headers=headers, json=second)

    assert response.status_code == 422
    assert response.json()["code"] == "IDEMPOTENCY_KEY_REUSED"


def test_publisher_wraps_domain_event_in_contract_envelope() -> None:
    app = fake_app()
    client = TestClient(app)
    valid_correlation_id = str(uuid7())

    response = client.post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": str(uuid7()), "X-Correlation-Id": valid_correlation_id},
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
    assert envelope.correlationId == f"corr-{valid_correlation_id}"
    event_payload_keys = set(envelope.payload)
    assert event_payload_keys == {
        "assessmentId",
        "subjectRef",
        "scenario",
        "decision",
        "score",
        "level",
        "policyVersion",
        "reasonCode",
        "reasonExplanation",
        "assessedAt",
        "evidenceRef",
        "assessmentSnapshotHash",
    }
    assert envelope.causationId is not None and is_prefixed_uuid7(envelope.causationId, "cmd")
    assert envelope.to_json_dict()["occurredAt"] == response.json()["assessedAt"]
    assert envelope.payload == {
        **response.json(),
        "evidenceRef": f"evid-{response.json()['assessmentId']}",
        "assessmentSnapshotHash": envelope.payload["assessmentSnapshotHash"],
    }
    assert envelope.payload["assessmentSnapshotHash"]


def test_subscriber_deduplicates_duplicate_event_id() -> None:
    envelope = EventEnvelope(
        eventId="evt-duplicate",
        eventType="RiskAssessmentResult",
        occurredAt="2026-07-05T10:30:00.000Z",
        correlationId=str(uuid7()),
        causationId=f"cmd-{uuid7()}",
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
        headers={"Idempotency-Key": str(uuid7()), "X-Correlation-Id": str(uuid7())},
        json={"subjectRef": "ord-123", "scenario": "order_risk", "context": {"riskScore": 0, "score": 999}},
    )

    assert response.status_code == 201
    assert response.json()["score"] == 0
    assert response.json()["decision"] == "ALLOW"


def test_correlation_id_policy_absent_valid_and_malformed() -> None:
    client = TestClient(fake_app())
    valid = str(uuid7())

    absent = client.post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": "not-a-v7"},
        json={"subjectRef": "ord-123", "scenario": "order_risk", "context": {}},
    )
    valid_response = client.post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": "not-a-v7", "X-Correlation-Id": valid},
        json={"subjectRef": "ord-123", "scenario": "order_risk", "context": {}},
    )
    malformed = client.post(
        "/api/v1/risk-assessments",
        headers={"Idempotency-Key": "not-a-v7", "X-Correlation-Id": "corr-bad"},
        json={"subjectRef": "ord-123", "scenario": "order_risk", "context": {}},
    )

    assert is_uuid7(absent.json()["correlationId"])
    assert valid_response.json()["correlationId"] == valid
    assert is_uuid7(malformed.json()["correlationId"])
    assert malformed.json()["correlationId"] != "corr-bad"


def test_request_id_is_server_assigned_and_caller_value_is_not_echoed() -> None:
    caller_request_id = str(uuid7())

    response = TestClient(fake_app()).post(
        "/api/v1/risk-assessments",
        headers={
            "Idempotency-Key": str(uuid7()),
            "X-Correlation-Id": str(uuid7()),
            "X-Request-Id": caller_request_id,
        },
        json={"subjectRef": "ord-123", "scenario": "order_risk", "context": {"riskScore": 10}},
    )

    assert response.status_code == 201
    assert response.headers["X-Request-Id"] != caller_request_id
    assert is_uuid7(response.headers["X-Request-Id"])


def test_journey_order_created_is_assessed_and_blocked_idempotently() -> None:
    publisher = InMemoryEventPublisher()
    service = RiskComplianceService(publisher, InMemoryAssessmentRepository())
    envelope = EventEnvelope(
        eventId=f"evt-{uuid7()}",
        eventType="JourneyOrderCreated",
        occurredAt="2026-07-05T10:30:00.000Z",
        correlationId=f"corr-{uuid7()}",
        causationId=f"cmd-{uuid7()}",
        producer="journey-order",
        schemaVersion=1,
        payload={
            "orderId": "ord-risky",
            "accountId": "acct-risky",
            "offerId": "offer-1",
            "travelerRefs": [{"travelerId": "tvl-1", "maskedDocumentRef": "11***9999", "travelerType": "ADULT"}],
            "segmentRefs": ["seg-1"],
            "createdAt": "2026-07-05T10:30:00.000Z",
        },
    )

    service.handle_event(envelope)
    service.handle_event(envelope)

    assert [published.eventType for published in publisher.envelopes] == ["RiskAssessmentResult", "RiskBlockApplied"]
    block = publisher.envelopes[1]
    assert block.payload == {
        "blockId": block.payload["blockId"],
        "subjectRef": "ord-risky",
        "scope": "ORDER",
        "reasonCode": "HIGH_RISK_SIGNAL",
        "policyVersion": "1.0.0",
        "evidenceRef": publisher.envelopes[0].payload["evidenceRef"],
        "blockedAt": block.payload["blockedAt"],
    }
    assert block.payload["blockId"].startswith("blk-")
    assert block.causationId == publisher.envelopes[0].eventId


def test_lift_block_endpoint_publishes_lift_event() -> None:
    app = fake_app()
    service = app.state.risk_service
    risky = EventEnvelope(
        eventId=f"evt-{uuid7()}",
        eventType="JourneyOrderCreated",
        occurredAt="2026-07-05T10:30:00.000Z",
        correlationId=f"corr-{uuid7()}",
        causationId=f"cmd-{uuid7()}",
        producer="journey-order",
        schemaVersion=1,
        payload={"orderId": "ord-lift", "accountId": "acct-lift", "travelerRefs": [{"maskedDocumentRef": "11***9999"}]},
    )
    service.handle_event(risky)

    response = TestClient(app).post(
        "/api/v1/risk-blocks/lift",
        headers={"Idempotency-Key": str(uuid7()), "X-Correlation-Id": str(uuid7())},
        json={"subjectRef": "ord-lift", "scope": "ORDER", "reasonCode": "MANUAL_REVIEW_CLEARED"},
    )

    assert response.status_code == 201
    body = response.json()
    assert body["allowId"].startswith("alw-")
    assert body["subjectRef"] == "ord-lift"
    assert body["scope"] == "ORDER"
    assert app.state.publisher.envelopes[-1].eventType == "RiskBlockLifted"
    assert app.state.publisher.envelopes[-1].payload == body
