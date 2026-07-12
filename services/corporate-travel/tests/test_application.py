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


def test_service_authorizes_and_closes_billing_period_from_events() -> None:
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
                "amount": {"currency": "USD", "minorUnits": 2500},
                "authorizationSnapshotRef": auth.snapshot_ref(),
            },
        )
    )
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
                "orderId": "order-1",
                "paymentIntentId": "pay-1",
                "capturedAmount": {"currency": "USD", "minorUnits": 2500},
                "authorizationSnapshotRef": auth.snapshot_ref(),
            },
        )
    )

    closed = service.close_billing_period(agreement_id=agreement.agreement_id, billing_period="2026-01").billing_period

    assert closed.status.value == "CLOSED"
    assert closed.total_amount.minor_units == 5000
    assert publisher.envelopes[-1].eventType == "CorporateBillingPeriodClosed"
    assert publisher.envelopes[-1].payload["statementHash"] == closed.statement_hash


def test_policy_check_reserves_budget_and_publishes_outbox_events() -> None:
    publisher = InMemoryEventPublisher()
    service = CorporateTravelService(publisher=publisher)
    agreement = service.create_agreement(**agreement_payload()).agreement

    result = service.check_policy_and_reserve(
        agreement_id=agreement.agreement_id,
        booking_ref="book-policy-1",
        employee_ref="emp-1",
        department_ref="dep-1",
        origin="BJS",
        destination="SHA",
        seat_class="SECOND_CLASS",
        amount={"currency": "USD", "minorUnits": 1200},
        requested_at="2026-01-01T00:00:00Z",
        departure_at="2026-01-05T00:00:00Z",
        trip_duration_minutes=300,
    )

    assert result.policy_result.decision.value == "COMPLIANT"
    assert result.approval_request is not None
    assert result.approval_request.status.value == "APPROVED"
    assert any(pool.reserved_minor == 1200 for pool in result.budget_pools)
    assert "TravelPolicyChecked" in [event.eventType for event in publisher.envelopes]
    assert "ApprovalGranted" in [event.eventType for event in publisher.envelopes]
    assert service.repository.outbox[-1].stream == "events:corporate-travel"


def test_department_budget_exceeded_is_blocked_without_finance_approval() -> None:
    publisher = InMemoryEventPublisher()
    service = CorporateTravelService(publisher=publisher)
    agreement = service.create_agreement(**agreement_payload()).agreement

    result = service.check_policy_and_reserve(
        agreement_id=agreement.agreement_id,
        booking_ref="book-policy-2",
        employee_ref="emp-1",
        department_ref="dep-1",
        origin="BJS",
        destination="SHA",
        seat_class="SECOND_CLASS",
        amount={"currency": "USD", "minorUnits": 10000100},
        requested_at="2026-01-01T00:00:00Z",
        departure_at="2026-01-05T00:00:00Z",
        trip_duration_minutes=300,
    )

    assert result.policy_result.decision.value == "REJECTED"
    assert result.approval_request is not None
    assert result.approval_request.current_level.value == 3
    assert all(pool.reserved_minor == 0 for pool in result.budget_pools)
    assert publisher.envelopes[-1].eventType == "ApprovalRequested"


def test_payment_commits_and_cancellation_releases_budget_reservation() -> None:
    service = CorporateTravelService(publisher=InMemoryEventPublisher())
    agreement = service.create_agreement(**agreement_payload()).agreement
    service.check_policy_and_reserve(
        agreement_id=agreement.agreement_id,
        booking_ref="book-policy-3",
        employee_ref="emp-1",
        department_ref="dep-1",
        origin="BJS",
        destination="SHA",
        seat_class="SECOND_CLASS",
        amount={"currency": "USD", "minorUnits": 1200},
        requested_at="2026-01-01T00:00:00Z",
        departure_at="2026-01-05T00:00:00Z",
        trip_duration_minutes=300,
    )

    committed = service.commit_budget_reservations(booking_ref="book-policy-3")
    released = service.release_budget_reservations(booking_ref="book-policy-3")

    assert committed
    assert all(pool.reserved_minor == 0 for pool in committed)
    assert any(pool.committed_minor == 1200 for pool in committed)
    assert released
    assert all(pool.committed_minor == 0 for pool in released)
