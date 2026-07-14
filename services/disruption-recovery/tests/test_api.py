from fastapi.testclient import TestClient
from train_ticket_platform.events import EventEnvelope

from disruption_recovery import create_app
from disruption_recovery.application.service import InMemoryStore
from train_ticket_platform.messaging import HandlerResult
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



def test_segment_ref_fanout_from_journey_order_index_and_service_alert_read_model() -> None:
    store = InMemoryStore()
    app = create_app(store=store)
    client = TestClient(app)
    service = app.state.disruption_recovery_service
    segment = "seg-0194f2e0-7b3e-7610-8000-000000000901"
    service.handle_journey_order_created(EventEnvelope(
        eventId="evt-0194f2e0-7b3e-7610-8000-000000000901",
        eventType="JourneyOrderCreated",
        producer="journey-order",
        payload={"orderId": "ord-0194f2e0-7b3e-7610-8000-000000000901", "segmentRefs": [segment]},
    ), "events:journey-order")

    body = report_body()
    body["segmentRef"] = segment
    body["affectedOrderIds"] = []
    response = client.post("/api/v1/disruptions", json=body, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000901"})

    assert response.status_code == 202, response.text
    data = response.json()
    assert [case["journeyOrderId"] for case in data["recoveryCases"]] == ["ord-0194f2e0-7b3e-7610-8000-000000000901"]
    alerts = client.get(f"/api/v1/service-alerts?incidentId={data['incident']['incidentId']}").json()
    assert alerts["total"] == 1
    assert alerts["items"][0]["affectedOrderIds"] == ["ord-0194f2e0-7b3e-7610-8000-000000000901"]


def test_fulfillment_segment_cancelled_opens_recovery_case_and_alert_read_model() -> None:
    store = InMemoryStore()
    app = create_app(store=store)
    service = app.state.disruption_recovery_service
    segment = "seg-0194f2e0-7b3e-7610-8000-000000000902"
    store.index_order_segments("ord-0194f2e0-7b3e-7610-8000-000000000902", (segment,))
    handled = service.handle_fulfillment_signal(EventEnvelope(
        eventId="evt-0194f2e0-7b3e-7610-8000-000000000902",
        eventType="SegmentCancelled",
        producer="fulfillment",
        correlationId="corr-0194f2e0-7b3e-7610-8000-000000000902",
        payload={
            "segmentRef": segment,
            "scheduledServiceRef": "ssch-0194f2e0-7b3e-7610-8000-000000000902",
            "serviceDate": "2026-08-02",
            "cancelledAt": "2026-08-02T09:00:00Z",
            "observedAt": "2026-08-02T08:55:00Z",
            "sourceSystem": "SYSTEM",
        },
    ), "events:fulfillment")

    assert handled is True
    incident = next(iter(store.incidents.values()))
    assert incident.disruptionType == "CANCELLATION"
    assert incident.affectedOrderIds == ("ord-0194f2e0-7b3e-7610-8000-000000000902",)
    case = next(iter(store.cases.values()))
    assert case.affectedScope["evidenceRef"] == "evt-0194f2e0-7b3e-7610-8000-000000000902"
    assert next(iter(store.service_alerts.values())).incidentId == incident.incidentId


def test_service_alert_read_model_can_rebuild_from_published_event() -> None:
    store = InMemoryStore()
    app = create_app(store=store)
    service = app.state.disruption_recovery_service
    payload = {
        "serviceAlertId": "sal-0194f2e0-7b3e-7610-8000-000000000903",
        "incidentId": "inc-0194f2e0-7b3e-7610-8000-000000000903",
        "disruptionType": "DELAY",
        "segmentRef": "seg-0194f2e0-7b3e-7610-8000-000000000903",
        "serviceDate": "2026-08-02",
        "audience": "AFFECTED_ORDERS",
        "affectedOrderIds": ["ord-0194f2e0-7b3e-7610-8000-000000000903"],
        "messageSummary": "Delay",
        "publishedAt": "2026-08-02T09:00:00Z",
    }

    assert service.handle_service_alert_published(EventEnvelope(
        eventId="evt-0194f2e0-7b3e-7610-8000-000000000903",
        eventType="ServiceAlertPublished",
        producer="disruption-recovery",
        payload=payload,
    ), "events:disruption-recovery") is True

    client = TestClient(app)
    response = client.get("/api/v1/service-alerts/sal-0194f2e0-7b3e-7610-8000-000000000903")
    assert response.status_code == 200
    assert response.json()["incidentId"] == payload["incidentId"]


def test_provider_segment_delayed_opens_recovery_case() -> None:
    store = InMemoryStore()
    app = create_app(store=store)
    service = app.state.disruption_recovery_service
    segment = "seg-0194f2e0-7b3e-7610-8000-000000000904"
    store.index_order_segments("ord-0194f2e0-7b3e-7610-8000-000000000904", (segment,))

    assert service.handle_provider_signal(EventEnvelope(
        eventId="evt-0194f2e0-7b3e-7610-8000-000000000904",
        eventType="ProviderSegmentDelayed",
        producer="provider-integration",
        correlationId="corr-0194f2e0-7b3e-7610-8000-000000000904",
        payload={
            "segmentRef": segment,
            "scheduledServiceRef": "ssch-0194f2e0-7b3e-7610-8000-000000000904",
            "serviceDate": "2026-08-02",
            "estimatedArrivalAt": "2026-08-02T10:30:00Z",
            "observedAt": "2026-08-02T09:00:00Z",
            "delayMinutes": 45,
            "sourceSystem": "PROVIDER",
        },
    ), "events:provider-integration") is True

    incident = next(iter(store.incidents.values()))
    assert incident.disruptionType == "DELAY"
    case = next(iter(store.cases.values()))
    assert case.affectedScope["disruptionType"] == "DELAY"


def test_segment_signal_waits_for_journey_order_projection() -> None:
    store = InMemoryStore()
    app = create_app(store=store)
    service = app.state.disruption_recovery_service
    segment = "seg-0194f2e0-7b3e-7610-8000-000000000905"
    signal = EventEnvelope(
        eventId="evt-0194f2e0-7b3e-7610-8000-000000000905",
        eventType="SegmentDelayed",
        producer="fulfillment",
        correlationId="corr-0194f2e0-7b3e-7610-8000-000000000905",
        payload={
            "segmentRef": segment,
            "scheduledServiceRef": "ssch-0194f2e0-7b3e-7610-8000-000000000905",
            "serviceDate": "2026-08-02",
            "estimatedArrivalAt": "2026-08-02T10:30:00Z",
            "observedAt": "2026-08-02T09:00:00Z",
        },
    )

    result = service.handle_fulfillment_signal(signal, "events:fulfillment")

    assert isinstance(result, HandlerResult)
    assert signal.eventId not in store.processed_events
    assert service.handle_journey_order_created(EventEnvelope(
        eventId="evt-0194f2e0-7b3e-7610-8000-000000000906",
        eventType="JourneyOrderCreated",
        producer="journey-order",
        payload={"orderId": "ord-0194f2e0-7b3e-7610-8000-000000000905", "segmentRefs": [segment]},
    ), "events:journey-order") is True
    assert signal.eventId in store.processed_events
    assert next(iter(store.incidents.values())).affectedOrderIds == ("ord-0194f2e0-7b3e-7610-8000-000000000905",)
