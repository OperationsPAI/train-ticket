from __future__ import annotations

from datetime import UTC, datetime

from fastapi.testclient import TestClient

from risk_compliance import InMemoryEventPublisher, InMemoryAssessmentRepository, RiskComplianceService, create_app
from risk_compliance.application import uuid7


def app_with_evaluations():
    return create_app(service=RiskComplianceService(InMemoryEventPublisher(), InMemoryAssessmentRepository()))


def post_eval(client: TestClient, account: str = "acct-normal", amount: int = 15000, **extra):
    payload = {
        "orderId": f"ord-{uuid7()}",
        "accountId": account,
        "travelerRefs": ["tvl-1"],
        "totalAmountMinor": amount,
        "currency": "CNY",
        "route": {"origin": "node-shanghai", "destination": "node-beijing"},
        "departureDate": "2026-07-20",
        "sourceIp": "203.0.113.45",
        "channelId": "web",
        **extra,
    }
    return client.post("/api/v1/risk-evaluations", headers={"Idempotency-Key": str(uuid7()), "X-Correlation-Id": str(uuid7())}, json=payload)


def test_normal_booking_passes_and_can_be_fetched() -> None:
    app = app_with_evaluations()
    client = TestClient(app)

    response = post_eval(client)

    assert response.status_code == 201
    body = response.json()
    assert body["verdict"] == "PASS"
    assert body["score"] < 30
    assert body["recommendedAction"] == "PROCEED"
    fetched = client.get(f"/api/v1/risk-evaluations/{body['evaluationId']}")
    assert fetched.status_code == 200
    assert fetched.json() == body
    assert app.state.publisher.envelopes[-1].eventType == "RiskEvaluationCompleted"


def test_four_orders_in_five_minutes_from_same_account_blocks() -> None:
    client = TestClient(app_with_evaluations())
    last = None
    for index in range(4):
        last = post_eval(client, account="acct-velocity", sourceIp=f"203.0.113.{100 + index}")
        assert last.status_code == 201

    assert last is not None
    body = last.json()
    assert body["verdict"] == "BLOCK"
    assert any(rule["ruleId"] == "VELOCITY_ACCOUNT_ORDER" and rule["result"] == "BLOCK" for rule in body["triggeredRules"])


def test_new_account_high_value_challenges() -> None:
    app = app_with_evaluations()
    app.state.risk_evaluation_service.repository.record_account_registered("acct-new", datetime.now(UTC))
    response = post_eval(TestClient(app), account="acct-new", amount=600_001)

    assert response.status_code == 201
    body = response.json()
    assert body["score"] >= 30
    assert body["verdict"] == "CHALLENGE"
    assert body["recommendedAction"] == "VERIFY_IDENTITY"


def test_same_route_bulk_adds_scalper_score() -> None:
    client = TestClient(app_with_evaluations())
    responses = [post_eval(client, account="acct-bulk", sourceIp=f"203.0.113.{120 + index}") for index in range(4)]

    assert responses[-1].json()["score"] >= 25
    assert responses[-1].json()["verdict"] in {"CHALLENGE", "BLOCK"}


def test_velocity_challenge_breach_raises_alert() -> None:
    app = app_with_evaluations()
    occurred_at = datetime.now(UTC)
    for _ in range(6):
        app.state.risk_evaluation_service.repository.record_payment_attempt("acct-payment-velocity", occurred_at)

    response = post_eval(TestClient(app), account="acct-payment-velocity", sourceIp="203.0.113.220")

    assert response.status_code == 201
    body = response.json()
    assert body["verdict"] == "CHALLENGE"
    assert body["score"] < 60
    assert any(rule["ruleId"] == "VELOCITY_ACCOUNT_PAYMENT" and rule["result"] == "CHALLENGE" for rule in body["triggeredRules"])
    assert [event.eventType for event in app.state.publisher.envelopes][-2:] == ["RiskEvaluationCompleted", "RiskAlertRaised"]
    assert app.state.publisher.envelopes[-1].payload["evaluationId"] == body["evaluationId"]


def test_staff_override_lifts_block_for_evaluation() -> None:
    client = TestClient(app_with_evaluations())
    blocked = None
    for index in range(4):
        blocked = post_eval(client, account="acct-override", sourceIp=f"203.0.113.{140 + index}")
    evaluation_id = blocked.json()["evaluationId"]

    response = client.post(
        f"/api/v1/risk-evaluations/{evaluation_id}/override",
        headers={"Idempotency-Key": str(uuid7()), "X-Correlation-Id": str(uuid7())},
        json={"staffId": "staff-1", "reason": "MANUAL_REVIEW_CLEARED"},
    )

    assert response.status_code == 200
    assert response.json()["verdict"] == "PASS"
