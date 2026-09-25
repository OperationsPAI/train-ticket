from __future__ import annotations

import os
from datetime import UTC, datetime, timedelta
from urllib.parse import quote, urlencode
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from corporate_travel.api import create_app
from train_ticket_platform.ids import new_uuid7


def create_payload() -> dict[str, object]:
    now = datetime.now(UTC).replace(microsecond=0)
    return {
        "corporateId": "corp-live",
        "agreementCode": "LIVE-2026",
        "legalName": "Live Corporate",
        "effectiveWindow": {
            "startsAt": (now - timedelta(days=30)).isoformat().replace("+00:00", "Z"),
            "endsAt": (now + timedelta(days=365)).isoformat().replace("+00:00", "Z"),
        },
        "priceRef": {"fareRuleRefs": ["fare-rule-live"], "ruleSetId": "rules", "ruleSetVersion": "1"},
        "monthlyCreditLimit": {"currency": "USD", "minorUnits": 100000},
        "billingCalendar": {
            "billingPeriod": now.strftime("%Y-%m"),
            "cutoffAt": (now + timedelta(days=31)).isoformat().replace("+00:00", "Z"),
            "dueAt": (now + timedelta(days=45)).isoformat().replace("+00:00", "Z"),
        },
        "contact": {"displayName": "L*** Finance"},
    }


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL"), reason="PostgreSQL owning-API test requires TEST_DATABASE_URL")
def test_owning_api_replays_and_continues_after_restart(monkeypatch: pytest.MonkeyPatch) -> None:
    import psycopg

    schema = "corporate_api_" + uuid4().hex
    with psycopg.connect(os.environ["TEST_DATABASE_URL"], autocommit=True) as admin:
        admin.execute(f"CREATE SCHEMA {schema}")
    try:
        separator = "&" if "?" in os.environ["TEST_DATABASE_URL"] else "?"
        database_url = os.environ["TEST_DATABASE_URL"] + separator + urlencode(
            {"options": f"-c search_path={schema}"}, quote_via=quote
        )
        monkeypatch.setenv("DATABASE_URL", database_url)
        monkeypatch.delenv("REDIS_URL", raising=False)
        create_key = str(new_uuid7())
        payload = create_payload()

        with TestClient(create_app()) as first:
            created = first.post("/api/v1/agreements", json=payload, headers={"Idempotency-Key": create_key})
            assert created.status_code == 201, created.text
            agreement_id = created.json()["agreementId"]

        with TestClient(create_app()) as restarted:
            replayed = restarted.post("/api/v1/agreements", json=payload, headers={"Idempotency-Key": create_key})
            assert replayed.status_code == 201, replayed.text
            assert replayed.json()["agreementId"] == agreement_id

            fetched = restarted.get(f"/api/v1/agreements/{agreement_id}")
            assert fetched.status_code == 200, fetched.text
            assert fetched.json()["status"] == "ACTIVE"

            authorized = restarted.post(
                f"/api/v1/agreements/{agreement_id}/authorize",
                json={"accountId": "acct-live", "costCenter": "CC-LIVE", "scope": {"routeScope": ["BJS-SHA"]}},
                headers={"Idempotency-Key": str(new_uuid7())},
            )
            assert authorized.status_code == 201, authorized.text
            assert authorized.json()["authorizationSnapshotRef"].startswith("auth-snap-")

            now = datetime.now(UTC).replace(microsecond=0)
            checked = restarted.post(
                f"/api/v1/agreements/{agreement_id}/policy-checks",
                json={
                    "employeeRef": "acct-live",
                    "departmentRef": "CC-LIVE",
                    "origin": "BJS",
                    "destination": "SHA",
                    "seatClass": "SECOND_CLASS",
                    "amount": {"currency": "USD", "minorUnits": 1200},
                    "requestedAt": now.isoformat().replace("+00:00", "Z"),
                    "departureAt": (now + timedelta(days=5)).isoformat().replace("+00:00", "Z"),
                    "tripDurationMinutes": 300,
                    "bookingRef": "book-live",
                },
                headers={"Idempotency-Key": str(new_uuid7())},
            )
            assert checked.status_code == 201, checked.text
            assert checked.json()["result"] in {"COMPLIANT", "APPROVAL_REQUIRED"}

        with psycopg.connect(database_url) as conn:
            assert conn.execute("SELECT count(*) FROM corporate_agreements").fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM idempotency_records").fetchone()[0] == 3
            assert conn.execute("SELECT count(*) FROM outbox").fetchone()[0] >= 3
    finally:
        with psycopg.connect(os.environ["TEST_DATABASE_URL"], autocommit=True) as admin:
            admin.execute(f"DROP SCHEMA {schema} CASCADE")
