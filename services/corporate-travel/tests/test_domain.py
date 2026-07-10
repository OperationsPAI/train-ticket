from __future__ import annotations

from datetime import UTC, datetime

import pytest

from corporate_travel.domain import (
    AgreementPriceRef,
    AuthorizedTraveler,
    BillingCalendar,
    BillingPeriod,
    CorporateAgreement,
    CorporateTravelError,
    EffectiveWindow,
    Money,
    StatementLine,
    StatementLineSource,
)


def agreement() -> CorporateAgreement:
    return CorporateAgreement(
        agreement_id="agr-1",
        corporate_id="corp-1",
        agreement_code="ACME-2026",
        legal_name="Acme Corp",
        version=1,
        effective_window=EffectiveWindow(datetime(2026, 1, 1, tzinfo=UTC), datetime(2027, 1, 1, tzinfo=UTC)),
        price_ref=AgreementPriceRef(("fare-rule-1",), "rules", "1"),
        monthly_credit_limit=Money("usd", 1_000_00),
        billing_calendar=BillingCalendar("2026-01", datetime(2026, 2, 1, tzinfo=UTC), datetime(2026, 2, 15, tzinfo=UTC)),
        contact={"displayName": "A*** Finance"},
    )


def traveler(account_id: str = "acct-1") -> AuthorizedTraveler:
    return AuthorizedTraveler(
        authorization_id=f"auth-{account_id}",
        account_id=account_id,
        corporate_id="corp-1",
        agreement_id="agr-1",
        cost_center="CC-100",
        project_code="P-1",
        scope={"routeScope": ["BJS-SHA"]},
        max_trip_amount=Money("USD", 50_00),
        can_delegate_booking=False,
        valid_from=datetime(2026, 1, 1, tzinfo=UTC),
        valid_until=datetime(2026, 12, 31, tzinfo=UTC),
    )


def test_agreement_activation_and_authorization_invariant() -> None:
    draft = agreement()
    with pytest.raises(CorporateTravelError):
        draft.authorize_traveler(traveler(), at=datetime(2026, 1, 2, tzinfo=UTC))

    active = draft.activate(at=datetime(2026, 1, 1, tzinfo=UTC))
    updated = active.authorize_traveler(traveler(), at=datetime(2026, 1, 2, tzinfo=UTC))

    assert updated.status.value == "ACTIVE"
    assert updated.authorized_travelers[0].snapshot_ref().startswith("auth-snap-")


def test_duplicate_active_authorization_same_scope_is_rejected() -> None:
    active = agreement().activate(at=datetime(2026, 1, 1, tzinfo=UTC))
    updated = active.authorize_traveler(traveler(), at=datetime(2026, 1, 2, tzinfo=UTC))

    with pytest.raises(CorporateTravelError, match="already exists"):
        updated.authorize_traveler(traveler(), at=datetime(2026, 1, 3, tzinfo=UTC))


def test_billing_period_freezes_hash_and_closes() -> None:
    period = BillingPeriod(
        statement_id="stmt-1",
        corporate_id="corp-1",
        agreement_id="agr-1",
        billing_period="2026-01",
        currency="USD",
        cutoff_at=datetime(2026, 2, 1, tzinfo=UTC),
        due_at=datetime(2026, 2, 15, tzinfo=UTC),
    )
    line = StatementLine(
        line_id="line-1",
        source_type=StatementLineSource.PAYMENT_CAPTURED,
        source_business_ref="pay-1",
        order_id="order-1",
        payment_intent_id="pay-1",
        amount=Money("USD", 1234),
        authorization_snapshot_ref="auth-snap-1",
        source_event_id="evt-1",
        occurred_at=datetime(2026, 1, 10, tzinfo=UTC),
    )

    closed = period.attach_line(line).freeze().close(at=datetime(2026, 2, 2, tzinfo=UTC))

    assert closed.status.value == "CLOSED"
    assert closed.statement_hash
    assert closed.total_amount.minor_units == 1234
    with pytest.raises(CorporateTravelError):
        closed.attach_line(line)
