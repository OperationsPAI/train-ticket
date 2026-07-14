from fastapi.testclient import TestClient

from disruption_recovery import create_app
from disruption_recovery.application.service import InMemoryStore
import re as _re


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
    assert rejected.status_code == 412


def event_payloads(store: InMemoryStore, event_type: str):
    return [event.payload for event in store.take_outbox() if event.eventType == event_type]


def test_auto_wait_events_use_contract_statuses() -> None:
    store = InMemoryStore()
    app = create_app(store=store)
    client = TestClient(app)
    response = client.post("/api/v1/disruptions", json=report_body(auto="WAIT"), headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000107"})
    assert response.status_code == 202
    events = store.take_outbox()
    generated = next(event.payload for event in events if event.eventType == "RecoveryOptionsGenerated")
    selected = next(event.payload for event in events if event.eventType == "RecoveryOptionSelected")
    started = next(event.payload for event in events if event.eventType == "RecoveryExecutionStarted")
    assert generated["status"] == "OPTIONS_GENERATED"
    assert selected["status"] == "EXECUTING_RECOVERY"
    assert started["status"] == "EXECUTING_RECOVERY"


def test_refund_execution_started_has_contract_status_and_downstream_summary() -> None:
    store = InMemoryStore()
    app = create_app(store=store)
    client = TestClient(app)
    created = client.post("/api/v1/disruptions", json=report_body(), headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000108"}).json()
    store.take_outbox()
    case = created["recoveryCases"][0]
    refund = next(o for o in case["optionSet"]["options"] if o["optionType"] == "REFUND")
    response = client.post(f"/api/v1/recovery-cases/{case['caseId']}/select-option", json={"optionId": refund["optionId"], "selectedBy": {"actorType": "USER", "actorId": "acc-1"}}, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000109"})
    assert response.status_code == 200
    started = event_payloads(store, "RecoveryExecutionStarted")[0]
    assert started["status"] == "EXECUTING_RECOVERY"
    assert _re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", started["idempotencyKey"])  # deterministic UUID-v7-shaped downstream key
    assert started["downstreamRequest"]["caseType"] == "REFUND"


def test_compensation_execution_started_has_downstream_summary() -> None:
    store = InMemoryStore()
    app = create_app(store=store)
    client = TestClient(app)
    created = client.post("/api/v1/disruptions", json=report_body("ord-0194f2e0-7b3e-7610-8000-000000000002"), headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000110"}).json()
    store.take_outbox()
    case = created["recoveryCases"][0]
    comp = next(o for o in case["optionSet"]["options"] if o["optionType"] == "COMPENSATION")
    response = client.post(f"/api/v1/recovery-cases/{case['caseId']}/select-option", json={"optionId": comp["optionId"], "selectedBy": {"actorType": "USER", "actorId": "acc-1"}}, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000111"})
    assert response.status_code == 200
    started = event_payloads(store, "RecoveryExecutionStarted")[0]
    assert started["status"] == "EXECUTING_RECOVERY"
    assert _re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", started["idempotencyKey"])
    assert started["downstreamRequest"]["issuanceSource"] == "DISRUPTION_COMP"


def test_select_option_precondition_failure_maps_to_412() -> None:
    app = create_app(store=InMemoryStore())
    client = TestClient(app)
    created = client.post("/api/v1/disruptions", json=report_body(auto="WAIT"), headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000112"}).json()
    case = created["recoveryCases"][0]
    wait = case["optionSet"]["options"][0]
    response = client.post(f"/api/v1/recovery-cases/{case['caseId']}/select-option", json={"optionId": wait["optionId"], "selectedBy": {"actorType": "USER", "actorId": "acc-1"}}, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000113"})
    assert response.status_code == 412


def test_transfer_management_missed_connection_report_values_are_accepted() -> None:
    app = create_app(store=InMemoryStore())
    client = TestClient(app)
    body = {
        "disruptionType": "MISSED_CONNECTION",
        "segmentRef": "seg-0194f2e0-7b3e-7610-8000-000000000222",
        "serviceDate": "2026-08-02",
        "evidence": {
            "evidenceRef": "con-0194f2e0-7b3e-7610-8000-000000000333",
            "sourceSystem": "TRANSFER_MANAGEMENT",
            "sourceRecordId": "evt-0194f2e0-7b3e-7610-8000-000000000444",
            "summary": "Protected missed connection",
        },
        "affectedOrderIds": ["ord-0194f2e0-7b3e-7610-8000-000000000555"],
        "reportedBy": {"actorType": "SYSTEM", "actorId": "transfer-management"},
    }
    response = client.post("/api/v1/disruptions", json=body, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000114"})
    assert response.status_code == 202
    data = response.json()
    assert data["disruption"]["disruptionType"] == "MISSED_CONNECTION"
    assert data["disruption"]["evidence"]["sourceSystem"] == "TRANSFER_MANAGEMENT"
    assert data["disruption"]["reportedBy"]["actorType"] == "SYSTEM"


class FakeTransferDownstream:
    def __init__(self) -> None:
        self.calls = []

    def open_refund_case(self, body, idempotency_key, correlation_id):
        return {"caseId": "psc-1"}

    def issue_compensation(self, body, idempotency_key, correlation_id):
        return {"benefitId": "ben-1"}

    def reaccommodate_connection(self, connection_id, body, idempotency_key, correlation_id):
        self.calls.append((connection_id, body, idempotency_key, correlation_id))
        return {"replacementConnection": {"connectionId": "con-replacement"}}


def missed_connection_body():
    body = report_body("ord-0194f2e0-7b3e-7610-8000-000000000556")
    body["disruptionType"] = "MISSED_CONNECTION"
    body.pop("scheduledServiceRef", None)
    body["evidence"] = {"evidenceRef": "con-0194f2e0-7b3e-7610-8000-000000000333", "sourceSystem": "TRANSFER_MANAGEMENT", "sourceRecordId": "evt-0194f2e0-7b3e-7610-8000-000000000444", "summary": "Protected missed connection"}
    body["reportedBy"] = {"actorType": "SYSTEM", "actorId": "transfer-management"}
    body["replacementWindow"] = {"plannedArrivalAt": "2026-08-02T10:00:00Z", "nextDepartureAt": "2026-08-02T11:00:00Z", "nextCutoffAt": "2026-08-02T10:55:00Z", "source": "SYSTEM"}
    return body


def test_transfer_management_missed_connection_gets_wait_and_reaccommodation_options() -> None:
    store = InMemoryStore()
    app = create_app(store=store)
    client = TestClient(app)
    response = client.post("/api/v1/disruptions", json=missed_connection_body(), headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000115"})
    assert response.status_code == 202, response.text
    case = response.json()["recoveryCases"][0]
    assert case["status"] == "AWAITING_USER_CHOICE"
    assert {option["optionType"] for option in case["optionSet"]["options"]} == {"WAIT", "REACCOMMODATION"}
    reacc = next(option for option in case["optionSet"]["options"] if option["optionType"] == "REACCOMMODATION")
    assert reacc["executionTarget"] == "TRANSFER_MANAGEMENT"
    assert reacc["reaccommodation"]["connectionId"].startswith("con-")


def test_reaccommodation_selection_posts_downstream_and_replay_keeps_key() -> None:
    store = InMemoryStore()
    downstream = FakeTransferDownstream()
    app = create_app(store=store, downstream=downstream)
    client = TestClient(app)
    created = client.post("/api/v1/disruptions", json=missed_connection_body(), headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000116"}).json()
    store.take_outbox()
    case = created["recoveryCases"][0]
    reacc = next(option for option in case["optionSet"]["options"] if option["optionType"] == "REACCOMMODATION")
    response = client.post(f"/api/v1/recovery-cases/{case['caseId']}/select-option", json={"optionId": reacc["optionId"], "selectedBy": {"actorType": "USER", "actorId": "acc-1"}}, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000117"})
    assert response.status_code == 200, response.text
    assert response.json()["status"] == "RECOVERED"
    assert len(downstream.calls) == 1
    first_key = downstream.calls[0][2]
    response = client.post(f"/api/v1/recovery-cases/{case['caseId']}/select-option", json={"optionId": reacc["optionId"], "selectedBy": {"actorType": "USER", "actorId": "acc-1"}}, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000117"})
    assert response.status_code == 200
    assert len(downstream.calls) == 1
    started = event_payloads(store, "RecoveryExecutionStarted")[0]
    assert started["downstreamRequest"]["connectionId"] == downstream.calls[0][0]
    assert started["downstreamRequest"]["idempotencyKey"] == first_key



def test_service_alert_read_model_is_queryable() -> None:
    store = InMemoryStore()
    app = create_app(store=store)
    client = TestClient(app)
    response = client.post("/api/v1/disruptions", json=report_body(), headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000118"})
    assert response.status_code == 202
    service_alert = response.json()["serviceAlert"]

    fetched = client.get(f"/api/v1/service-alerts/{service_alert['serviceAlertId']}")
    assert fetched.status_code == 200
    assert fetched.json()["incidentId"] == response.json()["incident"]["incidentId"]

    listed = client.get("/api/v1/service-alerts", params={"incidentId": response.json()["incident"]["incidentId"]})
    assert listed.status_code == 200
    assert listed.json()["total"] == 1
    assert listed.json()["items"][0]["serviceAlertId"] == service_alert["serviceAlertId"]
