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


class FakeTopology:
    def __init__(self, place_graph_version: str = "place-network:test-v1", fail: bool = False, missing: bool = False) -> None:
        self.place_graph_version = place_graph_version
        self.fail = fail
        self.missing = missing

    @property
    def enabled(self):
        return True

    def validate_connection_nodes(self, from_node_ref, to_node_ref, from_node_type, to_node_type):
        if self.missing:
            from transfer_management.topology import PlaceNetworkValidationError
            raise PlaceNetworkValidationError("place-network resource not found: /api/v1/transport-nodes/missing")
        if self.fail:
            from transfer_management.topology import PlaceNetworkUnavailable
            raise PlaceNetworkUnavailable("boom")
        return type("Snapshot", (), {"placeGraphVersion": self.place_graph_version, "mctAccessTimeMinutes": None})()

    def fetch_snapshot(self, from_node_ref, to_node_ref):
        if self.fail:
            from transfer_management.topology import PlaceNetworkUnavailable
            raise PlaceNetworkUnavailable("boom")
        return type("Snapshot", (), {"placeGraphVersion": self.place_graph_version, "mctAccessTimeMinutes": None})()


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


def setup_client(downstream: FakeDownstream | None = None, topology=None) -> tuple[TestClient, InMemoryStore, FakeDownstream]:
    store = InMemoryStore()
    downstream = downstream or FakeDownstream()
    return TestClient(create_app(store=store, downstream=downstream, topology=topology), raise_server_exceptions=False), store, downstream


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


def test_topology_weighted_mct_allows_ruleless_connection_and_uses_access_time(monkeypatch) -> None:
    import httpx

    from transfer_management.topology import PlaceNetworkClient

    original_client = httpx.Client

    def handler(request):
        bodies = {
            "/api/v1/transport-nodes/sta-a": {"nodeId": "sta-a", "placeId": "plc-a", "createdAt": "2026-01-01T00:00:00Z", "accessTimeMinutes": 11},
            "/api/v1/transport-nodes/sta-b": {"nodeId": "sta-b", "placeId": "plc-b", "createdAt": "2026-01-01T00:05:00Z", "accessTimeMinutes": 16},
            "/api/v1/places/plc-a": {"placeId": "plc-a", "placeType": "STATION"},
            "/api/v1/places/plc-b": {"placeId": "plc-b", "placeType": "STATION"},
        }
        return httpx.Response(200, json=bodies[str(request.url.path)])

    monkeypatch.setattr("transfer_management.topology.httpx.Client", lambda **kwargs: original_client(transport=httpx.MockTransport(handler), **kwargs))
    client, _, _ = setup_client(topology=PlaceNetworkClient("http://place-network.test"))
    plan = client.post("/api/v1/transfer-plans", headers={"Idempotency-Key": idem()}, json={"itineraryRef": "iti-weighted", "planningSnapshotVersion": 1, "travelerRefs": ["trav-1"], "journeyOrderId": "jo-weighted"})
    assert plan.status_code == 201, plan.text
    now = datetime.now(UTC).replace(microsecond=0)
    con = client.post("/api/v1/connections", headers={"Idempotency-Key": idem()}, json={"transferPlanId": plan.json()["transferPlanId"], "itineraryRef": "iti-weighted", "journeyOrderId": "jo-weighted", "previousSegmentRef": "seg-x", "nextSegmentRef": "seg-y", "travelerRefs": ["trav-1"], "fromNodeRef": "sta-a", "toNodeRef": "sta-b", "fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "contractId": "cct-x", "contractType": "PROTECTED", "window": {"plannedArrivalAt": rfc3339_utc(now), "nextDepartureAt": rfc3339_utc(now + timedelta(minutes=60)), "nextCutoffAt": rfc3339_utc(now + timedelta(minutes=60))}})
    assert con.status_code == 201, con.text
    data = con.json()
    assert data["window"]["mctMinutes"] == 27
    assert data["latestEvaluation"]["requiredMinutes"] == 27
    assert data["latestEvaluation"]["placeGraphVersion"] == "place-network:sta-a@2026-01-01T00:00:00Z:sta-b@2026-01-01T00:05:00Z"


def test_topology_weighted_mct_accepts_contracted_zero_access_time(monkeypatch) -> None:
    import httpx

    from transfer_management.topology import PlaceNetworkClient

    original_client = httpx.Client

    def handler(request):
        bodies = {
            "/api/v1/transport-nodes/sta-zero-a": {"nodeId": "sta-zero-a", "placeId": "plc-a", "accessTimeMinutes": 0},
            "/api/v1/transport-nodes/sta-zero-b": {"nodeId": "sta-zero-b", "placeId": "plc-b", "accessTimeMinutes": 0},
            "/api/v1/places/plc-a": {"placeId": "plc-a", "placeType": "STATION"},
            "/api/v1/places/plc-b": {"placeId": "plc-b", "placeType": "STATION"},
        }
        return httpx.Response(200, json=bodies[str(request.url.path)])

    monkeypatch.setattr("transfer_management.topology.httpx.Client", lambda **kwargs: original_client(transport=httpx.MockTransport(handler), **kwargs))
    client, _, _ = setup_client(topology=PlaceNetworkClient("http://place-network.test"))
    plan = client.post("/api/v1/transfer-plans", headers={"Idempotency-Key": idem()}, json={"itineraryRef": "iti-zero-weighted", "planningSnapshotVersion": 1, "travelerRefs": ["trav-1"], "journeyOrderId": "jo-zero-weighted"})
    assert plan.status_code == 201, plan.text
    now = datetime.now(UTC).replace(microsecond=0)
    con = client.post("/api/v1/connections", headers={"Idempotency-Key": idem()}, json={"transferPlanId": plan.json()["transferPlanId"], "itineraryRef": "iti-zero-weighted", "journeyOrderId": "jo-zero-weighted", "previousSegmentRef": "seg-x", "nextSegmentRef": "seg-y", "travelerRefs": ["trav-1"], "fromNodeRef": "sta-zero-a", "toNodeRef": "sta-zero-b", "fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "contractId": "cct-x", "contractType": "PROTECTED", "window": {"plannedArrivalAt": rfc3339_utc(now), "nextDepartureAt": rfc3339_utc(now + timedelta(minutes=1)), "nextCutoffAt": rfc3339_utc(now + timedelta(minutes=1))}})
    assert con.status_code == 201, con.text
    data = con.json()
    assert data["window"]["mctMinutes"] == 0
    assert data["latestEvaluation"]["requiredMinutes"] == 0


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


def test_reaccommodate_registers_replacement_and_recovered_event() -> None:
    client, store, downstream = setup_client()
    con = create_plan_and_connection(client)
    now = datetime.now(UTC).replace(microsecond=0)
    res = client.post("/api/v1/segment-status-reports", headers={"Idempotency-Key": idem(), "X-Correlation-Id": f"corr-{idem()}"}, json={"segmentRef": "seg-a", "reportType": "DELAY", "reportedBy": {"actorType": "SYSTEM", "actorId": "sys"}, "sourceSystem": "OPERATIONS", "sourceRecordId": "r-reacc", "observedAt": rfc3339_utc(now), "estimatedArrivalAt": rfc3339_utc(now + timedelta(hours=2))})
    assert res.status_code == 202, res.text
    assert downstream.calls
    body = {"caseId": "rcv-1", "replacementWindow": {"plannedArrivalAt": rfc3339_utc(now + timedelta(minutes=10)), "nextDepartureAt": rfc3339_utc(now + timedelta(minutes=70)), "nextCutoffAt": rfc3339_utc(now + timedelta(minutes=65)), "source": "SYSTEM"}}
    res = client.post(f"/api/v1/connections/{con['connectionId']}/reaccommodate", headers={"Idempotency-Key": idem()}, json=body)
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["connection"]["status"] == "RECOVERED"
    replacement_id = data["replacementConnection"]["connectionId"]
    assert data["connection"]["replacementConnectionId"] == replacement_id
    assert data["replacementConnection"]["replacementOfConnectionId"] == con["connectionId"]
    recovered_events = [e for e in store.take_outbox() if e.eventType == "ConnectionRecovered"]
    assert recovered_events[-1].payload["replacementConnectionId"] == replacement_id


def test_reaccommodate_precondition_and_self_completion_skip() -> None:
    client, store, downstream = setup_client()
    con = create_plan_and_connection(client)
    now = datetime.now(UTC).replace(microsecond=0)
    client.post("/api/v1/segment-status-reports", headers={"Idempotency-Key": idem(), "X-Correlation-Id": f"corr-{idem()}"}, json={"segmentRef": "seg-a", "reportType": "DELAY", "reportedBy": {"actorType": "SYSTEM", "actorId": "sys"}, "sourceSystem": "OPERATIONS", "sourceRecordId": "r-skip", "observedAt": rfc3339_utc(now), "estimatedArrivalAt": rfc3339_utc(now + timedelta(hours=2))})
    body = {"caseId": "rcv-1", "replacementWindow": {"plannedArrivalAt": rfc3339_utc(now + timedelta(minutes=10)), "nextDepartureAt": rfc3339_utc(now + timedelta(minutes=70)), "source": "SYSTEM"}}
    assert client.post(f"/api/v1/connections/{con['connectionId']}/reaccommodate", headers={"Idempotency-Key": idem()}, json=body).status_code == 200
    rejected = client.post(f"/api/v1/connections/{con['connectionId']}/reaccommodate", headers={"Idempotency-Key": idem()}, json=body)
    assert rejected.status_code == 412
    store.take_outbox()
    service = client.app.state.transfer_management_service
    env = EventEnvelope(eventId="evt-" + idem(), eventType="RecoveryCompleted", producer="disruption-recovery", correlationId="corr-" + idem(), causationId="evt-" + idem(), payload={"caseId": "rcv-1", "journeyOrderId": "jo-1"})
    assert service.handle_recovery_event(env, "events:disruption-recovery") is True
    assert [e for e in store.take_outbox() if e.eventType == "ConnectionRecovered"] == []


def test_fulfillment_segment_delayed_event_marks_connection_at_risk_when_mct_is_violated_before_cutoff() -> None:
    from datetime import UTC, datetime
    from train_ticket_platform.events import EventEnvelope
    from transfer_management.application.service import TransferManagementService

    store = InMemoryStore()
    service = TransferManagementService(store)
    cid = "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c201"
    cmd = "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c202"
    rule = service.create_mct_rule({"fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "minimumMinutes": 20, "conditions": {}, "validFrom": "2026-07-01T00:00:00Z"}, cid, cmd)
    service.publish_mct_rule(rule["mctRuleId"], {"publishedBy": {"actorType": "SYSTEM", "actorId": "test"}}, cid, cmd)
    plan = service.create_plan({"itineraryRef": "iti-1", "planningSnapshotVersion": 1, "travelerRefs": ["tvl-1"], "journeyOrderId": "ord-1"}, cid, cmd)
    connection = service.register_connection({"transferPlanId": plan["transferPlanId"], "itineraryRef": "iti-1", "previousSegmentRef": "seg-prev", "nextSegmentRef": "seg-next", "travelerRefs": ["tvl-1"], "fromNodeRef": "node-a", "toNodeRef": "node-b", "fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "contractId": "contract-1", "contractType": "PLATFORM_ASSISTED", "window": {"plannedArrivalAt": "2026-07-05T10:00:00Z", "nextDepartureAt": "2026-07-05T10:45:00Z", "nextCutoffAt": "2026-07-05T10:45:00Z", "mctMinutes": 20}}, cid, cmd)

    envelope = EventEnvelope(eventId="evt-0194f2e0-7b3e-7610-8284-5c26e8b0c203", eventType="SegmentDelayed", occurredAt=datetime(2026, 7, 5, 9, 50, tzinfo=UTC), correlationId=cid, causationId=cmd, producer="fulfillment", payload={"segmentRef": "seg-prev", "scheduledServiceRef": "svc-1", "serviceDate": "2026-07-05", "estimatedArrivalAt": "2026-07-05T10:30:00Z", "observedAt": "2026-07-05T09:50:00Z", "sourceSystem": "OPS"})

    assert service.handle_fulfillment_event(envelope, "events:fulfillment") is True
    updated = service.get_connection(connection["connectionId"])
    assert updated["status"] == "AT_RISK"
    assert updated["latestEvaluation"]["riskLevel"] == "AT_RISK"
    assert store.mark_processed(envelope.eventId, "events:fulfillment") is False


def test_topology_degradation_path_marks_evaluation_degraded() -> None:
    client, _, _ = setup_client(topology=FakeTopology(fail=True))
    con = create_plan_and_connection(client, "SELF_TRANSFER")
    now = datetime.now(UTC).replace(microsecond=0)
    res = client.post("/api/v1/segment-status-reports", headers={"Idempotency-Key": idem()}, json={"segmentRef": "seg-a", "reportType": "DELAY", "reportedBy": {"actorType": "SYSTEM", "actorId": "sys"}, "sourceSystem": "OPERATIONS", "sourceRecordId": "r-topo", "observedAt": rfc3339_utc(now), "estimatedArrivalAt": rfc3339_utc(now + timedelta(minutes=35))})
    assert res.status_code == 202, res.text
    evaluation = res.json()["updatedConnections"][0]["latestEvaluation"]
    assert evaluation["degraded"] is True
    assert evaluation["degradedReasons"] == ["PLACE_NETWORK_UNAVAILABLE"]


def test_topology_place_graph_version_is_recorded() -> None:
    client, _, _ = setup_client(topology=FakeTopology("place-network:test-v2"))
    con = create_plan_and_connection(client, "SELF_TRANSFER")
    evaluation = con["latestEvaluation"]
    assert evaluation["placeGraphVersion"] == "place-network:test-v2"


def test_active_risk_policy_version_drives_evaluation() -> None:
    client, _, _ = setup_client()
    res = client.post("/api/v1/risk-policies", headers={"Idempotency-Key": idem()}, json={"version": "ops-aggressive-v1", "thresholds": {"tightMinutes": 999, "atRiskMinutes": 120}, "createdBy": {"actorType": "OPERATIONS", "actorId": "ops"}})
    assert res.status_code == 201, res.text
    policy_id = res.json()["riskPolicyId"]
    res = client.post(f"/api/v1/risk-policies/{policy_id}/activate", headers={"Idempotency-Key": idem()}, json={"activatedBy": {"actorType": "OPERATIONS", "actorId": "ops"}})
    assert res.status_code == 200, res.text
    con = create_plan_and_connection(client, "SELF_TRANSFER")
    assert con["status"] == "AT_RISK"
    assert con["latestEvaluation"]["riskPolicyVersion"] == "ops-aggressive-v1"
    assert any(reason == "THRESHOLD_AT_RISK_BUFFER_LT_120_MIN" for reason in con["latestEvaluation"]["reasons"])


def test_risk_policy_activation_retires_previous_active_and_active_is_immutable() -> None:
    client, store, _ = setup_client()
    first = client.post("/api/v1/risk-policies", headers={"Idempotency-Key": idem()}, json={"version": "ops-v1", "thresholds": {"tightMinutes": 10, "atRiskMinutes": 0}, "createdBy": {"actorType": "OPERATIONS", "actorId": "ops"}}).json()
    second = client.post("/api/v1/risk-policies", headers={"Idempotency-Key": idem()}, json={"version": "ops-v2", "thresholds": {"tightMinutes": 15, "atRiskMinutes": 5}, "createdBy": {"actorType": "OPERATIONS", "actorId": "ops"}}).json()
    assert client.post(f"/api/v1/risk-policies/{first['riskPolicyId']}/activate", headers={"Idempotency-Key": idem()}, json={"activatedBy": {"actorType": "OPERATIONS", "actorId": "ops"}}).status_code == 200
    assert client.post(f"/api/v1/risk-policies/{second['riskPolicyId']}/activate", headers={"Idempotency-Key": idem()}, json={"activatedBy": {"actorType": "OPERATIONS", "actorId": "ops"}}).status_code == 200
    assert store.get_risk_policy(first["riskPolicyId"]).status.value == "RETIRED"
    assert client.get("/api/v1/risk-policies/active").json()["version"] == "ops-v2"
    assert client.post(f"/api/v1/risk-policies/{first['riskPolicyId']}/activate", headers={"Idempotency-Key": idem()}, json={"activatedBy": {"actorType": "OPERATIONS", "actorId": "ops"}}).status_code == 412


def test_missing_place_network_node_registration_returns_validation_failed() -> None:
    client, _, _ = setup_client(topology=FakeTopology(missing=True))
    create_rule(client)
    plan = client.post("/api/v1/transfer-plans", headers={"Idempotency-Key": idem()}, json={"itineraryRef": "iti-missing-node", "planningSnapshotVersion": 1, "travelerRefs": ["trav-1"], "journeyOrderId": "jo-missing-node"})
    assert plan.status_code == 201, plan.text
    now = datetime.now(UTC).replace(microsecond=0)
    res = client.post("/api/v1/connections", headers={"Idempotency-Key": idem()}, json={"transferPlanId": plan.json()["transferPlanId"], "itineraryRef": "iti-missing-node", "journeyOrderId": "jo-missing-node", "previousSegmentRef": "seg-a", "nextSegmentRef": "seg-b", "travelerRefs": ["trav-1"], "fromNodeRef": "missing", "toNodeRef": "sta", "fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "contractId": "cct-missing", "contractType": "SELF_TRANSFER", "window": {"plannedArrivalAt": rfc3339_utc(now), "nextDepartureAt": rfc3339_utc(now + timedelta(minutes=60)), "nextCutoffAt": rfc3339_utc(now + timedelta(minutes=50))}})
    assert res.status_code == 422, res.text
    assert res.json()["code"] == "VALIDATION_FAILED"


def test_failed_activation_does_not_retire_the_active_policy() -> None:
    client, store, _ = setup_client()
    def make(version):
        res = client.post("/api/v1/risk-policies", headers={"Idempotency-Key": idem()}, json={"version": version, "thresholds": {"tightMinutes": 15, "atRiskMinutes": 5}, "createdBy": {"actorType": "OPERATIONS", "actorId": "ops"}})
        assert res.status_code == 201, res.text
        return res.json()["riskPolicyId"]
    p1, p2 = make("v1"), make("v2")
    for pid in (p1, p2):
        res = client.post(f"/api/v1/risk-policies/{pid}/activate", headers={"Idempotency-Key": idem()}, json={"activatedBy": {"actorType": "OPERATIONS", "actorId": "ops"}})
        assert res.status_code == 200, res.text
    res = client.post(f"/api/v1/risk-policies/{p1}/activate", headers={"Idempotency-Key": idem()}, json={"activatedBy": {"actorType": "OPERATIONS", "actorId": "ops"}})
    assert res.status_code == 412, res.text
    res = client.get("/api/v1/risk-policies/active")
    assert res.json()["riskPolicyId"] == p2, res.text


def test_connection_missed_auto_rebooks_with_no_charge() -> None:
    client, store, downstream = setup_client()
    con = create_plan_and_connection(client)
    now = datetime.now(UTC).replace(microsecond=0)
    res = client.post("/api/v1/segment-status-reports", headers={"Idempotency-Key": idem(), "X-Correlation-Id": f"corr-{idem()}"}, json={"segmentRef": "seg-a", "reportType": "DELAY", "reportedBy": {"actorType": "SYSTEM", "actorId": "sys"}, "sourceSystem": "OPERATIONS", "sourceRecordId": "r-auto", "observedAt": rfc3339_utc(now), "estimatedArrivalAt": rfc3339_utc(now + timedelta(hours=2)), "rebookingSuggestions": [{"newSegmentRef": "seg-c", "newDepartureTime": rfc3339_utc(now + timedelta(hours=3)), "additionalCost": 2500}]})
    assert res.status_code == 202, res.text
    updated = res.json()["updatedConnections"]
    recovered = [item for item in updated if item["connectionId"] == con["connectionId"]][0]
    assert recovered["status"] == "RECOVERED"
    assert recovered["replacementConnectionId"]
    assert downstream.calls == []
    events = [event for event in store.take_outbox() if event.eventType == "AutoRebookingCompleted"]
    assert events[-1].payload["additionalCost"] == 0
    assert events[-1].payload["rebookingSuggestion"]["newSegmentRef"] == "seg-c"


def test_guaranteed_missed_without_alternative_offers_remaining_leg_refund() -> None:
    client, store, _ = setup_client()
    con = create_plan_and_connection(client)
    now = datetime.now(UTC).replace(microsecond=0)
    res = client.post("/api/v1/segment-status-reports", headers={"Idempotency-Key": idem(), "X-Correlation-Id": f"corr-{idem()}"}, json={"segmentRef": "seg-a", "reportType": "DELAY", "reportedBy": {"actorType": "SYSTEM", "actorId": "sys"}, "sourceSystem": "OPERATIONS", "sourceRecordId": "r-refund", "observedAt": rfc3339_utc(now), "estimatedArrivalAt": rfc3339_utc(now + timedelta(hours=2))})
    assert res.status_code == 202, res.text
    assert client.get(f"/api/v1/connections/{con['connectionId']}").json()["status"] == "MISSED"
    failed = [event for event in store.take_outbox() if event.eventType == "RebookingFailed"]
    assert failed[-1].payload["refundOffered"] is True
    assert failed[-1].payload["refundScope"] == "REMAINING_LEGS"


def test_disruption_train_delayed_event_consumed_as_missed_connection() -> None:
    from transfer_management.application.service import TransferManagementService

    store = InMemoryStore()
    downstream = FakeDownstream()
    service = TransferManagementService(store, downstream)
    rule = service.create_mct_rule({"fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "minimumMinutes": 20, "conditions": {}, "validFrom": "2025-01-01T00:00:00Z"}, "corr-test", "cmd-test")
    service.publish_mct_rule(rule["mctRuleId"], {"publishedBy": {"actorType": "OPERATIONS", "actorId": "ops"}, "publishReason": "test"}, "corr-test", "cmd-test")
    plan = service.create_plan({"itineraryRef": "iti-delay", "planningSnapshotVersion": 1, "travelerRefs": ["trav-1"], "journeyOrderId": "jo-delay"}, "corr-test", "cmd-test")
    now = datetime.now(UTC).replace(microsecond=0)
    con = service.register_connection({"transferPlanId": plan["transferPlanId"], "itineraryRef": "iti-delay", "journeyOrderId": "jo-delay", "previousSegmentRef": "train-a", "nextSegmentRef": "train-b", "travelerRefs": ["trav-1"], "fromNodeRef": "sta", "toNodeRef": "sta", "fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "contractId": "cct-delay", "contractType": "PROTECTED", "window": {"plannedArrivalAt": rfc3339_utc(now), "nextDepartureAt": rfc3339_utc(now + timedelta(minutes=60))}}, "corr-test", "cmd-test")
    env = EventEnvelope(eventId="evt-" + idem(), eventType="TrainDelayed", producer="disruption-recovery", correlationId="corr-" + idem(), causationId="evt-" + idem(), payload={"segmentRef": "train-a", "estimatedArrivalAt": rfc3339_utc(now + timedelta(hours=2)), "observedAt": rfc3339_utc(now)})
    assert service.handle_recovery_event(env, "events:disruption-recovery") is True
    assert service.get_connection(con["connectionId"])["status"] == "MISSED"
    assert any(event.eventType == "ConnectionMissed" for event in store.take_outbox())
