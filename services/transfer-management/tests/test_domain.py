from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest

from transfer_management.application.service import InMemoryStore, TransferManagementService, folded_uuid7
from transfer_management.domain import ConnectionStatus, PreconditionFailed


def test_folded_uuid7_shape_is_stable() -> None:
    key = folded_uuid7("connection:missed:1")
    assert key == folded_uuid7("connection:missed:1")
    assert UUID(key).version == 7


def test_connection_missed_never_returns_feasible() -> None:
    service = TransferManagementService(InMemoryStore())
    now = datetime(2026, 1, 1, tzinfo=UTC)
    rule = service.create_mct_rule({"fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "minimumMinutes": 20, "conditions": {}, "validFrom": "2025-01-01T00:00:00Z"}, "corr-test", "cmd-test")
    service.publish_mct_rule(rule["mctRuleId"], {"publishedBy": {"actorType": "OPERATIONS", "actorId": "ops"}, "publishReason": "test"}, "corr-test", "cmd-test")
    plan = service.create_plan({"itineraryRef": "iti-1", "planningSnapshotVersion": 1, "travelerRefs": ["trav-1"], "journeyOrderId": "jo-1"}, "corr-test", "cmd-test")
    con = service.register_connection({"transferPlanId": plan["transferPlanId"], "itineraryRef": "iti-1", "journeyOrderId": "jo-1", "previousSegmentRef": "seg-a", "nextSegmentRef": "seg-b", "travelerRefs": ["trav-1"], "fromNodeRef": "sta-a", "toNodeRef": "sta-a", "fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "contractId": "cct-x", "contractType": "SELF_TRANSFER", "window": {"plannedArrivalAt": now.isoformat().replace("+00:00", "Z"), "nextDepartureAt": (now + timedelta(minutes=60)).isoformat().replace("+00:00", "Z"), "nextCutoffAt": (now + timedelta(minutes=50)).isoformat().replace("+00:00", "Z")}}, "corr-test", "cmd-test")
    assert con["status"] == "FEASIBLE"
    service.report_segment_status({"segmentRef": "seg-a", "reportType": "DELAY", "reportedBy": {"actorType": "SYSTEM", "actorId": "sys"}, "sourceSystem": "OPERATIONS", "sourceRecordId": "r1", "observedAt": (now + timedelta(minutes=1)).isoformat().replace("+00:00", "Z"), "estimatedArrivalAt": (now + timedelta(minutes=70)).isoformat().replace("+00:00", "Z")}, "corr-test", "cmd-test")
    missed = service.get_connection(con["connectionId"])
    assert missed["status"] == "MISSED"
    service.report_segment_status({"segmentRef": "seg-a", "reportType": "ARRIVAL", "reportedBy": {"actorType": "SYSTEM", "actorId": "sys"}, "sourceSystem": "OPERATIONS", "sourceRecordId": "r2", "observedAt": (now + timedelta(minutes=2)).isoformat().replace("+00:00", "Z"), "actualArrivalAt": now.isoformat().replace("+00:00", "Z")}, "corr-test", "cmd-test")
    assert service.get_connection(con["connectionId"])["status"] == "MISSED"


def test_terminal_connection_rejects_illegal_transition() -> None:
    service = TransferManagementService(InMemoryStore())
    now = datetime(2026, 1, 1, tzinfo=UTC)
    service.create_mct_rule({"fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "minimumMinutes": 20, "conditions": {}, "validFrom": "2025-01-01T00:00:00Z"}, "corr-test", "cmd-test")
    # Domain transition table rejects invalid transitions directly.
    from transfer_management.domain import Connection, ConnectionWindow, ContractType, NodeType, RiskEvaluation, RiskLevel, TransferCategory
    ev = RiskEvaluation("tre-x", RiskLevel.FEASIBLE, "mct-x", 1, 30, 20, (), now)
    conn = Connection("con-x", "tpl-x", "iti", "a", "b", ("t",), "n", "n", NodeType.STATION, NodeType.STATION, TransferCategory.SAME_STATION, "cct", ContractType.SELF_TRANSFER, ConnectionStatus.MISSED, ev, ConnectionWindow.build(now, now + timedelta(hours=1), now + timedelta(minutes=50), 20), now, now)
    with pytest.raises(PreconditionFailed):
        conn.transition(ConnectionStatus.FEASIBLE, now)


def test_published_mct_rule_is_immutable() -> None:
    service = TransferManagementService(InMemoryStore())
    rule = service.create_mct_rule({"fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "minimumMinutes": 20, "conditions": {}, "validFrom": "2025-01-01T00:00:00Z"}, "corr-test", "cmd-test")
    service.publish_mct_rule(rule["mctRuleId"], {"publishedBy": {"actorType": "OPERATIONS", "actorId": "ops"}, "publishReason": "test"}, "corr-test", "cmd-test")
    with pytest.raises(PreconditionFailed):
        service.update_mct_rule(rule["mctRuleId"], {"minimumMinutes": 25})
