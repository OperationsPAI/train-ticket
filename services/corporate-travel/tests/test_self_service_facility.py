from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from corporate_travel.application import CorporateTravelService, InMemoryCorporateTravelRepository
from corporate_travel.domain import CorporateTravelError, Money, SelfServiceFacility

# Expressed around now for the same reason test_application.py does it: the
# window is a negotiated annual contract, and a literal would expire.
_NOW = datetime.now(UTC).replace(microsecond=0)
STARTS_AT = (_NOW - timedelta(days=30)).strftime("%Y-%m-%dT%H:%M:%SZ")
ENDS_AT = (_NOW + timedelta(days=365)).strftime("%Y-%m-%dT%H:%M:%SZ")


def facility() -> SelfServiceFacility:
    return SelfServiceFacility()


def agreement_request(minor_units: int) -> dict[str, object]:
    return {
        "corporate_id": "corp-1",
        "agreement_code": "ACME-2026",
        "legal_name": "Acme Corp",
        "effective_window": {"startsAt": STARTS_AT, "endsAt": ENDS_AT},
        "price_ref": {"fareRuleRefs": ["fare-rule-1"], "ruleSetId": "rules", "ruleSetVersion": "1"},
        "monthly_credit_limit": {"currency": "CNY", "minorUnits": minor_units},
        "billing_calendar": {
            "billingPeriod": "MONTHLY",
            "cutoffAt": STARTS_AT,
            "dueAt": ENDS_AT,
        },
    }


def test_the_shipped_facility_admits_the_credit_the_platform_extends() -> None:
    """The number the platform's own accounts travel on is inside the facility.

    A facility below it would refuse the create the workload sends, so the
    benchmark the service ships has to be at least that one.
    """
    assert facility().admits(Money("CNY", 5_000_000))


def test_a_credit_above_the_facility_does_not_open() -> None:
    service = CorporateTravelService(repository=InMemoryCorporateTravelRepository())
    above = facility().max_monthly_credit_minor + 1
    with pytest.raises(CorporateTravelError):
        service.create_agreement(**agreement_request(above))


def test_a_credit_inside_the_facility_opens_and_is_stored() -> None:
    repository = InMemoryCorporateTravelRepository()
    service = CorporateTravelService(repository=repository)
    result = service.create_agreement(**agreement_request(5_000_000))
    stored = repository.get_agreement(result.agreement.agreement_id)
    assert stored.monthly_credit_limit == Money("CNY", 5_000_000)
    assert stored.status.value == "ACTIVE"


def test_the_facility_refuses_a_negative_credit_it_could_not_hold() -> None:
    with pytest.raises(CorporateTravelError):
        SelfServiceFacility(max_monthly_credit_minor=-1)
