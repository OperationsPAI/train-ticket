from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from corporate_travel.api import create_app


SERVICE_ROOT = Path(__file__).resolve().parents[1]


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


def test_default_migrations_path_points_to_service_migrations(monkeypatch) -> None:
    calls: dict[str, object] = {}

    class StubPool:
        def __init__(self, config: object) -> None:
            calls["config"] = config

        def close(self) -> None:
            calls["closed"] = True

    class StubConfig:
        @classmethod
        def from_env(cls) -> object:
            return object()

    def fake_run_migrations(pool: object, migrations_dir: object) -> None:
        calls["pool"] = pool
        calls["migrations_dir"] = migrations_dir

    monkeypatch.delenv("MIGRATIONS_DIR", raising=False)
    monkeypatch.setattr("corporate_travel.api.DatabaseConfig", StubConfig)
    monkeypatch.setattr("corporate_travel.api.DatabasePool", StubPool)
    monkeypatch.setattr("corporate_travel.api.run_migrations", fake_run_migrations)

    app = create_app()

    assert app.state.corporate_travel_service is not None
    assert calls["migrations_dir"] == SERVICE_ROOT / "migrations"


def test_migration_creates_processed_events_table_not_legacy_inbox() -> None:
    migration_sql = (SERVICE_ROOT / "migrations" / "001_corporate_travel.sql").read_text(encoding="utf-8")

    assert "CREATE TABLE IF NOT EXISTS processed_events" in migration_sql
    assert "event_id TEXT PRIMARY KEY" in migration_sql
    assert "corporate_travel_inbox" not in migration_sql


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


def test_policy_check_endpoint_is_backward_compatible_addition() -> None:
    client = TestClient(create_app())
    agreement_id = client.post("/agreements", json=create_payload()).json()["agreementId"]

    response = client.post(
        f"/agreements/{agreement_id}/policy-checks",
        json={
            "employeeRef": "emp-1",
            "departmentRef": "dep-1",
            "origin": "BJS",
            "destination": "SHA",
            "seatClass": "SECOND_CLASS",
            "amount": {"currency": "USD", "minorUnits": 1200},
            "requestedAt": "2026-01-01T00:00:00Z",
            "departureAt": "2026-01-05T00:00:00Z",
            "tripDurationMinutes": 300,
            "bookingRef": "book-api-1",
        },
    )

    assert response.status_code == 201
    assert response.json()["result"] == "COMPLIANT"
    assert response.json()["approvalRequest"]["status"] == "APPROVED"
