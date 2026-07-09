from fastapi.testclient import TestClient

from disruption_recovery import create_app
from disruption_recovery.application.service import InMemoryStore


def report_body(order="ord-0194f2e0-7b3e-7610-8000-000000000001", auto=None):
    body = {
        "disruptionType": "SERVICE_DELAY",
        "scheduledServiceRef": "ssch-0194f2e0-7b3e-7610-8000-000000000001",
        "segmentRef": "seg-0194f2e0-7b3e-7610-8000-000000000002",
        "serviceDate": "2026-08-02",
        "evidence": {"evidenceRef": "ev-1", "sourceSystem": "ADMIN", "sourceRecordId": "row-1", "summary": "Delay"},
        "affectedOrderIds": [order],
        "reportedBy": {"actorType": "OPERATIONS", "actorId": "ops-1"},
    }
    if auto:
        body["autoRecovery"] = auto
    return body


def test_report_creates_incident_and_case_and_idempotency_replay() -> None:
    app = create_app(store=InMemoryStore())
    client = TestClient(app)
    key = "0194f2e0-7b3e-7610-8000-000000000100"
    response = client.post("/api/v1/disruptions", json=report_body(), headers={"Idempotency-Key": key})
    assert response.status_code == 202
    data = response.json()
    assert data["incident"]["status"] == "CONFIRMED"
    assert data["recoveryCases"][0]["status"] == "AWAITING_USER_CHOICE"
    replay = client.post("/api/v1/disruptions", json=report_body(), headers={"Idempotency-Key": key})
    assert replay.status_code == 202
    assert replay.json()["incident"]["incidentId"] == data["incident"]["incidentId"]


def test_select_compensation_recovers_and_close_terminal_only() -> None:
    app = create_app(store=InMemoryStore())
    client = TestClient(app)
    created = client.post("/api/v1/disruptions", json=report_body(), headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000101"}).json()
    case = created["recoveryCases"][0]
    comp = next(o for o in case["optionSet"]["options"] if o["optionType"] == "COMPENSATION")
    selected = client.post(f"/api/v1/recovery-cases/{case['caseId']}/select-option", json={"optionId": comp["optionId"], "selectedBy": {"actorType": "USER", "actorId": "acc-1"}}, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000102"})
    assert selected.status_code == 200
    assert selected.json()["status"] == "RECOVERED"
    closed = client.post(f"/api/v1/recovery-cases/{case['caseId']}/close", json={"closedBy": {"actorType": "OPERATIONS", "actorId": "ops-1"}, "closeReason": "done"}, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000103"})
    assert closed.status_code == 200
    assert closed.json()["status"] == "CLOSED"


def test_manual_review_cannot_close_directly() -> None:
    app = create_app(store=InMemoryStore())
    client = TestClient(app)
    created = client.post("/api/v1/disruptions", json=report_body(), headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000104"}).json()
    case = created["recoveryCases"][0]
    manual = next(o for o in case["optionSet"]["options"] if o["optionType"] == "MANUAL")
    selected = client.post(f"/api/v1/recovery-cases/{case['caseId']}/select-option", json={"optionId": manual["optionId"], "selectedBy": {"actorType": "CUSTOMER_SERVICE", "actorId": "cs-1"}}, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000105"})
    assert selected.status_code == 200
    assert selected.json()["status"] == "MANUAL_REVIEW"
    rejected = client.post(f"/api/v1/recovery-cases/{case['caseId']}/close", json={"closedBy": {"actorType": "OPERATIONS", "actorId": "ops-1"}, "closeReason": "bad"}, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000106"})
    assert rejected.status_code == 422
