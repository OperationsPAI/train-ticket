from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import UUID

from fastapi.testclient import TestClient

from train_ticket_platform.events import EventEnvelope, rfc3339_utc
from train_ticket_platform.ids import new_uuid7

from transfer_management import create_app
from transfer_management.application.service import InMemoryStore
from transfer_management.downstream import DownstreamError


def idem() -> str:
    return new_uuid7()


class FakeDownstream:
    def __init__(self, failures: int = 0) -> None:
        self.calls = []
        self.failures = failures

    def report_missed_connection(self, body, idempotency_key, correlation_id):
        if self.failures > 0:
            self.failures -= 1
            raise DownstreamError("boom", "HTTP_500")
        UUID(idempotency_key)
        assert UUID(idempotency_key).version == 7
        assert correlation_id.startswith("corr-")
        assert body["disruptionType"] == "MISSED_CONNECTION"
        assert body["evidence"]["sourceSystem"] == "TRANSFER_MANAGEMENT"
        self.calls.append((body, idempotency_key, correlation_id))
        return {"disruption": {"disruptionId": "drp-1"}, "incident": {"incidentId": "inc-1"}, "recoveryCases": [{"caseId": "rcv-1"}]}


def setup_client(downstream: FakeDownstream | None = None) -> tuple[TestClient, InMemoryStore, FakeDownstream]:
    store = InMemoryStore()
    downstream = downstream or FakeDownstream()
    return TestClient(create_app(store=store, downstream=downstream), raise_server_exceptions=False), store, downstream


def create_rule(client: TestClient) -> str:
    res = client.post("/api/v1/mct-rules", headers={"Idempotency-Key": idem()}, json={"fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "minimumMinutes": 20, "conditions": {}, "validFrom": "2025-01-01T00:00:00Z"})
    assert res.status_code == 201, res.text
    rule_id = res.json()["mctRuleId"]
    res = client.post(f"/api/v1/mct-rules/{rule_id}/publish", headers={"Idempotency-Key": idem()}, json={"publishedBy": {"actorType": "OPERATIONS", "actorId": "ops"}, "publishReason": "test"})
    assert res.status_code == 200, res.text
    return rule_id


def create_plan_and_connection(client: TestClient, contract_type: str = "PROTECTED") -> dict:
    create_rule(client)
    plan = client.post("/api/v1/transfer-plans", headers={"Idempotency-Key": idem()}, json={"itineraryRef": "iti-1", "planningSnapshotVersion": 1, "travelerRefs": ["trav-1"], "journeyOrderId": "jo-1"})
    assert plan.status_code == 201, plan.text
    now = datetime.now(UTC).replace(microsecond=0)
    con = client.post("/api/v1/connections", headers={"Idempotency-Key": idem()}, json={"transferPlanId": plan.json()["transferPlanId"], "itineraryRef": "iti-1", "journeyOrderId": "jo-1", "previousSegmentRef": "seg-a", "nextSegmentRef": "seg-b", "travelerRefs": ["trav-1"], "fromNodeRef": "sta", "toNodeRef": "sta", "fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "contractId": "cct-1", "contractType": contract_type, "window": {"plannedArrivalAt": rfc3339_utc(now), "nextDepartureAt": rfc3339_utc(now + timedelta(minutes=60)), "nextCutoffAt": rfc3339_utc(now + timedelta(minutes=50))}})
    assert con.status_code == 201, con.text
    return con.json()


def test_protected_missed_opens_recovery_and_consumes_completion() -> None:
    client, store, downstream = setup_client()
    con = create_plan_and_connection(client)
    now = datetime.now(UTC).replace(microsecond=0)
    res = client.post("/api/v1/segment-status-reports", headers={"Idempotency-Key": idem(), "X-Correlation-Id": f"corr-{idem()}"}, json={"segmentRef": "seg-a", "reportType": "DELAY", "reportedBy": {"actorType": "SYSTEM", "actorId": "sys"}, "sourceSystem": "OPERATIONS", "sourceRecordId": "r1", "observedAt": rfc3339_utc(now), "estimatedArrivalAt": rfc3339_utc(now + timedelta(hours=2))})
    assert res.status_code == 202, res.text
    updated = res.json()["updatedConnections"][0]
    assert updated["status"] == "MISSED"
    assert updated["recovery"]["recoveryTriggerStatus"] == "OPENED"
    assert updated["recovery"]["caseIds"] == ["rcv-1"]
    assert len(downstream.calls) == 1
    service = client.app.state.transfer_management_service
    env = EventEnvelope(eventId="evt-" + idem(), eventType="RecoveryCompleted", producer="disruption-recovery", correlationId="corr-" + idem(), causationId="evt-" + idem(), payload={"caseId": "rcv-1", "journeyOrderId": "jo-1"})
    assert service.handle_recovery_event(env, "events:disruption-recovery") is True
    assert service.handle_recovery_event(env, "events:disruption-recovery") is True
    got = client.get(f"/api/v1/connections/{con['connectionId']}")
    assert got.status_code == 200
    assert got.json()["status"] == "RECOVERED"
    recovered_events = [e for e in store.take_outbox() if e.eventType == "ConnectionRecovered"]
    assert len(recovered_events) == 1


def test_self_transfer_missed_does_not_open_recovery() -> None:
    client, _, downstream = setup_client()
    con = create_plan_and_connection(client, "SELF_TRANSFER")
    now = datetime.now(UTC).replace(microsecond=0)
    res = client.post("/api/v1/segment-status-reports", headers={"Idempotency-Key": idem()}, json={"segmentRef": "seg-b", "reportType": "CANCELLED", "reportedBy": {"actorType": "SYSTEM", "actorId": "sys"}, "sourceSystem": "OPERATIONS", "sourceRecordId": "r2", "observedAt": rfc3339_utc(now), "cancelledAt": rfc3339_utc(now)})
    assert res.status_code == 202, res.text
    assert client.get(f"/api/v1/connections/{con['connectionId']}").json()["status"] == "MISSED"
    assert downstream.calls == []


def test_event_envelope_ids_and_payload_fields() -> None:
    client, store, _ = setup_client()
    create_rule(client)
    events = store.take_outbox()
    assert events
    for event in events:
        assert event.eventId.startswith("evt-")
        assert UUID(event.eventId[4:]).version == 7
        assert event.correlationId.startswith("corr-")
        assert event.causationId and event.causationId.startswith("cmd-")
        assert isinstance(event.to_json_dict()["occurredAt"], str)


def test_downstream_failure_returns_503_after_missed_is_persisted_and_retry_opens_once() -> None:
    downstream = FakeDownstream(failures=1)
    client, _, _ = setup_client(downstream)
    con = create_plan_and_connection(client)
    now = datetime.now(UTC).replace(microsecond=0)
    body = {"segmentRef": "seg-a", "reportType": "DELAY", "reportedBy": {"actorType": "SYSTEM", "actorId": "sys"}, "sourceSystem": "OPERATIONS", "sourceRecordId": "r503", "observedAt": rfc3339_utc(now), "estimatedArrivalAt": rfc3339_utc(now + timedelta(hours=2))}
    res = client.post("/api/v1/segment-status-reports", headers={"Idempotency-Key": idem(), "X-Correlation-Id": f"corr-{idem()}"}, json=body)
    assert res.status_code == 503, res.text
    persisted = client.get(f"/api/v1/connections/{con['connectionId']}").json()
    assert persisted["status"] == "MISSED"
    key = persisted["recovery"]["outboundIdempotencyKey"]
    assert UUID(key).version == 7
    retry = client.post("/api/v1/segment-status-reports", headers={"Idempotency-Key": idem(), "X-Correlation-Id": f"corr-{idem()}"}, json=body)
    assert retry.status_code == 202, retry.text
    recovered = client.get(f"/api/v1/connections/{con['connectionId']}").json()
    assert recovered["recovery"]["recoveryTriggerStatus"] == "OPENED"
    assert recovered["recovery"]["caseIds"] == ["rcv-1"]
    assert recovered["recovery"]["outboundIdempotencyKey"] == key
    assert len(downstream.calls) == 1


def test_no_matching_mct_rule_rejects_connection_registration() -> None:
    client, _, _ = setup_client()
    plan = client.post("/api/v1/transfer-plans", headers={"Idempotency-Key": idem()}, json={"itineraryRef": "iti-no-rule", "planningSnapshotVersion": 1, "travelerRefs": ["trav-1"], "journeyOrderId": "jo-no-rule"})
    assert plan.status_code == 201, plan.text
    now = datetime.now(UTC).replace(microsecond=0)
    con = client.post("/api/v1/connections", headers={"Idempotency-Key": idem()}, json={"transferPlanId": plan.json()["transferPlanId"], "itineraryRef": "iti-no-rule", "journeyOrderId": "jo-no-rule", "previousSegmentRef": "seg-x", "nextSegmentRef": "seg-y", "travelerRefs": ["trav-1"], "fromNodeRef": "sta", "toNodeRef": "sta", "fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "contractId": "cct-x", "contractType": "PROTECTED", "window": {"plannedArrivalAt": rfc3339_utc(now), "nextDepartureAt": rfc3339_utc(now + timedelta(minutes=60)), "nextCutoffAt": rfc3339_utc(now + timedelta(minutes=50))}})
    assert con.status_code == 422, con.text
    assert "NO_PUBLISHED_MCT_RULE" in con.text


def test_evaluate_plan_without_published_rule_marks_unserviceable() -> None:
    client, store, _ = setup_client()
    con = create_plan_and_connection(client)
    rule_id = next(iter(store.mct_rules))
    plan_id = con["transferPlanId"]
    res = client.post(f"/api/v1/mct-rules/{rule_id}/retire", headers={"Idempotency-Key": idem()}, json={"retiredBy": {"actorType": "OPERATIONS", "actorId": "ops"}, "retireReason": "gap"})
    assert res.status_code == 200, res.text
    res = client.post(f"/api/v1/transfer-plans/{plan_id}/evaluate", headers={"Idempotency-Key": idem()}, json={"planningSnapshotVersion": 2})
    assert res.status_code == 200, res.text
    assert res.json()["status"] == "UNSERVICEABLE"
    events = [event for event in store.take_outbox() if event.eventType == "TransferPlanEvaluated"]
    assert events[-1].payload["unserviceableReasons"] == ["NO_PUBLISHED_MCT_RULE"]


def test_rule_selection_is_deterministic_newest_published_version_wins() -> None:
    client, store, _ = setup_client()
    first = create_rule(client)
    second = create_rule(client)
    rules = store.mct_rules
    from transfer_management.application.service import TransferManagementService
    service = TransferManagementService(store)
    sample = rules[second]
    picked = service._find_published_rule(sample.fromNodeType, sample.toNodeType, sample.transferCategory, sample.validFrom)
    expected = max((rules[first], rules[second]), key=lambda r: (r.version, r.mctRuleId))
    assert picked.mctRuleId == expected.mctRuleId
