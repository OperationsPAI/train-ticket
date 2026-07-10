from __future__ import annotations

from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.messaging import InMemoryEventPublisher

from corporate_travel.application import CorporateTravelService


def agreement_payload() -> dict[str, object]:
    return {
        "corporate_id": "corp-1",
        "agreement_code": "ACME-2026",
        "legal_name": "Acme Corp",
        "effective_window": {"startsAt": "2026-01-01T00:00:00Z", "endsAt": "2027-01-01T00:00:00Z"},
        "price_ref": {"fareRuleRefs": ["fare-rule-1"], "ruleSetId": "rules", "ruleSetVersion": "1"},
        "monthly_credit_limit": {"currency": "USD", "minorUnits": 100000},
        "billing_calendar": {"billingPeriod": "2026-01", "cutoffAt": "2026-02-01T00:00:00Z", "dueAt": "2026-02-15T00:00:00Z"},
        "contact": {"displayName": "A*** Finance"},
    }


def test_service_creates_active_agreement_and_publishes_activation() -> None:
    publisher = InMemoryEventPublisher()
    service = CorporateTravelService(publisher=publisher)

    result = service.create_agreement(**agreement_payload())

    assert result.agreement.status.value == "ACTIVE"
    assert [event.eventType for event in publisher.envelopes] == ["CorporateAgreementCreated", "CorporateAgreementActivated"]
    assert publisher.envelopes[1].payload["monthlyCreditLimit"] == {"currency": "USD", "minorUnits": 100000}


def test_service_authorizes_and_closes_billing_period_after_order_and_payment_facts() -> None:
    publisher = InMemoryEventPublisher()
    service = CorporateTravelService(publisher=publisher)
    agreement = service.create_agreement(**agreement_payload()).agreement
    auth = service.authorize_traveler(agreement_id=agreement.agreement_id, account_id="acct-1", cost_center="CC-100")

    service.handle_event(
        EventEnvelope(
            eventId="evt-order-1",
            eventType="JourneyOrderConfirmed",
            occurredAt="2026-01-10T00:00:00Z",
            correlationId="corr-test",
            causationId="cmd-test",
            producer="journey-order",
            schemaVersion=1,
            payload={
                "agreementId": agreement.agreement_id,
                "billingPeriod": "2026-01",
                "orderId": "order-1",
                "monetarySummary": {"total": {"currency": "USD", "minorUnits": 2500}},
                "authorizationSnapshotRef": auth.snapshot_ref(),
            },
        )
    )

    assert service.repository.find_open_period(agreement.agreement_id, "2026-01") is None

    service.handle_event(
        EventEnvelope(
            eventId="evt-pay-1",
            eventType="PaymentCaptured",
            occurredAt="2026-01-10T00:05:00Z",
            correlationId="corr-test",
            causationId="cmd-test",
            producer="payment",
            schemaVersion=1,
            payload={
                "agreementId": agreement.agreement_id,
                "billingPeriod": "2026-01",
                "businessRef": "order-1",
                "paymentIntentId": "pay-1",
                "capturedAmount": {"currency": "USD", "minorUnits": 2500},
                "authorizationSnapshotRef": auth.snapshot_ref(),
            },
        )
    )

    closed = service.close_billing_period(agreement_id=agreement.agreement_id, billing_period="2026-01").billing_period

    assert closed.status.value == "CLOSED"
    assert len(closed.lines) == 1
    assert closed.lines[0].source_type.value == "PAYMENT_CAPTURED"
    assert closed.total_amount.minor_units == 2500
    assert publisher.envelopes[-1].eventType == "CorporateBillingPeriodClosed"
    assert publisher.envelopes[-1].payload["statementHash"] == closed.statement_hash


def test_service_waits_for_order_confirmation_before_billing_payment_capture() -> None:
    service = CorporateTravelService()
    agreement = service.create_agreement(**agreement_payload()).agreement

    service.handle_event(
        EventEnvelope(
            eventId="evt-pay-early",
            eventType="PaymentCaptured",
            occurredAt="2026-01-10T00:05:00Z",
            correlationId="corr-test",
            causationId="cmd-test",
            producer="payment",
            schemaVersion=1,
            payload={
                "agreementId": agreement.agreement_id,
                "billingPeriod": "2026-01",
                "businessRef": "order-early",
                "paymentIntentId": "pay-early",
                "capturedAmount": {"currency": "USD", "minorUnits": 1200},
            },
        )
    )

    assert service.repository.find_open_period(agreement.agreement_id, "2026-01") is None

    service.handle_event(
        EventEnvelope(
            eventId="evt-order-early",
            eventType="JourneyOrderConfirmed",
            occurredAt="2026-01-10T00:00:00Z",
            correlationId="corr-test",
            causationId="cmd-test",
            producer="journey-order",
            schemaVersion=1,
            payload={
                "agreementId": agreement.agreement_id,
                "billingPeriod": "2026-01",
                "orderId": "order-early",
                "monetarySummary": {"total": {"currency": "USD", "minorUnits": 1200}},
            },
        )
    )

    period = service.repository.find_open_period(agreement.agreement_id, "2026-01")

    assert period is not None
    assert len(period.lines) == 1
    assert period.total_amount.minor_units == 1200
