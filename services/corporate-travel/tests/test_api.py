from __future__ import annotations

from fastapi.testclient import TestClient

from corporate_travel.api import create_app


def create_payload() -> dict[str, object]:
    return {
        "corporateId": "corp-1",
        "agreementCode": "ACME-2026",
        "legalName": "Acme Corp",
        "effectiveWindow": {"startsAt": "2026-01-01T00:00:00Z", "endsAt": "2027-01-01T00:00:00Z"},
        "priceRef": {"fareRuleRefs": ["fare-rule-1"], "ruleSetId": "rules", "ruleSetVersion": "1"},
        "monthlyCreditLimit": {"currency": "USD", "minorUnits": 100000},
        "billingCalendar": {"billingPeriod": "2026-01", "cutoffAt": "2026-02-01T00:00:00Z", "dueAt": "2026-02-15T00:00:00Z"},
        "contact": {"displayName": "A*** Finance"},
    }


def test_health_and_agreement_endpoints() -> None:
    client = TestClient(create_app())

    health = client.get("/health")
    assert health.status_code == 200
    assert health.json()["status"] == "ok"

    created = client.post("/agreements", json=create_payload())
    assert created.status_code == 201
    agreement_id = created.json()["agreementId"]

    fetched = client.get(f"/agreements/{agreement_id}")
    assert fetched.status_code == 200
    assert fetched.json()["status"] == "ACTIVE"

    authorized = client.post(
        f"/agreements/{agreement_id}/authorize",
        json={"accountId": "acct-1", "costCenter": "CC-100", "scope": {"routeScope": ["BJS-SHA"]}},
    )
    assert authorized.status_code == 201
    assert authorized.json()["authorizationSnapshotRef"].startswith("auth-snap-")


def test_unknown_agreement_returns_canonical_error() -> None:
    client = TestClient(create_app())

    response = client.get("/agreements/missing")

    assert response.status_code == 404
    assert response.json()["code"] == "NOT_FOUND"
