import os
from pathlib import Path
from urllib.request import urlopen
from uuid import uuid4
import json

import psycopg
import pytest
from fastapi.testclient import TestClient
from psycopg_pool import ConnectionPool

from disruption_recovery import create_app
from disruption_recovery.adapters.storage.postgres import PostgresDisruptionRecoveryStore
from disruption_recovery.domain import DisruptionType
from disruption_recovery.downstream import DownstreamHttpClient
from train_ticket_platform.ids import new_uuid7
from train_ticket_platform.storage import PostgresIdempotencyStore


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL") or not os.getenv("TEST_LIVE_ORDER_URL"), reason="PostgreSQL and a live order are required")
def test_report_response_snapshots_events_and_replay_share_canonical_type():
    with urlopen(os.environ["TEST_LIVE_ORDER_URL"], timeout=10) as response:
        order = json.load(response)
    segment = order["segments"][0]
    schema = "disruption_types_" + uuid4().hex
    url = os.environ["TEST_DATABASE_URL"]
    with psycopg.connect(url, autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(url, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    for migration in sorted((Path(__file__).parents[1] / "migrations").glob("*.sql")):
                        conn.execute(migration.read_text())

                def app():
                    return create_app(store=PostgresDisruptionRecoveryStore(pool), idempotency_store=PostgresIdempotencyStore(pool), downstream=DownstreamHttpClient())

                for disruption_type in DisruptionType:
                    key = new_uuid7()
                    body = {
                        "disruptionType": disruption_type.value,
                        "segmentRef": segment["segmentRef"],
                        "serviceDate": segment["departureTime"][:10],
                        "evidence": {"evidenceRef": key, "sourceSystem": "ADMIN", "sourceRecordId": key, "summary": "Canonical type persistence verification"},
                        "affectedOrderIds": [order["orderId"]],
                        "reportedBy": {"actorType": "OPERATIONS", "actorId": "persistence-verification"},
                        "affectedBookings": [{"orderId": order["orderId"], "travelerId": order["travelerRefs"][0], "segmentRef": segment["segmentRef"], "originalDeparture": segment["departureTime"], "ticketPrice": order["monetarySummary"]["total"]}],
                    }
                    with TestClient(app()) as client:
                        created = client.post("/api/v1/disruptions", json=body, headers={"Idempotency-Key": key})
                    assert created.status_code == 202, created.text
                    result = created.json()
                    assert result["disruption"]["disruptionType"] == disruption_type
                    assert result["disruption"]["classification"]["type"] == disruption_type
                    assert result["incident"]["disruptionType"] == disruption_type
                    assert result["recoveryCases"][0]["affectedScope"]["disruptionType"] == disruption_type
                    assert result["recoveryCases"][0]["status"] == "AWAITING_USER_CHOICE"
                    assert result["recoveryCases"][0]["optionSet"]["requiresUserChoice"] is True
                    assert {option["optionType"] for option in result["recoveryCases"][0]["optionSet"]["options"]} == {"WAIT", "REFUND", "COMPENSATION", "MANUAL"}
                    assert result["serviceAlert"]["disruptionType"] == disruption_type
                    with pool.connection() as conn:
                        events_before = conn.execute("SELECT count(*) FROM outbox").fetchone()[0]
                        persisted_case = conn.execute("SELECT data FROM recovery_case_snapshots WHERE id=%s", (result["recoveryCases"][0]["caseId"],)).fetchone()[0]
                        declared = conn.execute("SELECT envelope->'payload' FROM outbox WHERE envelope->>'eventType'='DisruptionDeclared' AND envelope->'payload'->>'disruptionId'=%s", (result["disruption"]["disruptionId"],)).fetchone()[0]
                    assert persisted_case["affectedScope"]["disruptionType"] == disruption_type
                    assert persisted_case["status"] == "AWAITING_USER_CHOICE"
                    assert declared["type"] == disruption_type
                    with TestClient(app()) as client:
                        replay = client.post("/api/v1/disruptions", json=body, headers={"Idempotency-Key": key})
                        stored = client.get("/api/v1/recovery-cases/" + result["recoveryCases"][0]["caseId"])
                        body["disruptionType"] = disruption_type.value.lower()
                        invalid = client.post("/api/v1/disruptions", json=body, headers={"Idempotency-Key": new_uuid7()})
                    assert replay.status_code == 202
                    assert replay.json() == result
                    assert stored.status_code == 200
                    assert stored.json()["affectedScope"]["disruptionType"] == disruption_type
                    assert invalid.status_code == 422
                    with pool.connection() as conn:
                        assert conn.execute("SELECT count(*) FROM outbox").fetchone()[0] == events_before
                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM incident_snapshots").fetchone()[0] == len(DisruptionType)
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
