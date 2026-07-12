from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest

from train_ticket_platform.events import EventEnvelope
from transfer_management.application.service import InMemoryStore, TransferManagementService, folded_uuid7
from transfer_management.domain import ConnectionStatus, DomainError, PreconditionFailed


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


def test_large_hub_mct_enforced_rejects_ten_minute_gap_and_accepts_thirty() -> None:
    service = TransferManagementService(InMemoryStore())
    now = datetime(2026, 1, 1, 10, tzinfo=UTC)
    plan = service.create_plan({"itineraryRef": "iti-mct", "planningSnapshotVersion": 1, "travelerRefs": ["trav-1"], "journeyOrderId": "jo-mct"}, "corr-test", "cmd-test")
    base = {"transferPlanId": plan["transferPlanId"], "itineraryRef": "iti-mct", "journeyOrderId": "jo-mct", "previousSegmentRef": "seg-a", "nextSegmentRef": "seg-b", "travelerRefs": ["trav-1"], "fromNodeRef": "北京南", "toNodeRef": "北京南", "fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION", "contractId": "cct-mct", "contractType": "PROTECTED"}
    with pytest.raises(DomainError) as exc:
        service.register_connection(base | {"window": {"plannedArrivalAt": now.isoformat().replace("+00:00", "Z"), "nextDepartureAt": (now + timedelta(minutes=10)).isoformat().replace("+00:00", "Z")}}, "corr-test", "cmd-test")
    assert "INSUFFICIENT_CONNECTION_TIME" in str(exc.value)
    con = service.register_connection(base | {"nextSegmentRef": "seg-c", "window": {"plannedArrivalAt": now.isoformat().replace("+00:00", "Z"), "nextDepartureAt": (now + timedelta(minutes=30)).isoformat().replace("+00:00", "Z")}}, "corr-test", "cmd-test")
    assert con["status"] in {"FEASIBLE", "TIGHT"}
    assert con["window"]["mctMinutes"] == 25


def test_cross_mode_train_to_metro_uses_35_minute_mct_and_instructions() -> None:
    service = TransferManagementService(InMemoryStore())
    now = datetime(2026, 1, 1, 10, tzinfo=UTC)
    plan = service.create_plan({"itineraryRef": "iti-metro", "planningSnapshotVersion": 1, "travelerRefs": ["trav-1"]}, "corr-test", "cmd-test")
    con = service.register_connection({"transferPlanId": plan["transferPlanId"], "itineraryRef": "iti-metro", "previousSegmentRef": "seg-train", "nextSegmentRef": "metro-1", "travelerRefs": ["trav-1"], "fromNodeRef": "北京南", "toNodeRef": "北京南地铁", "fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "CROSS_STATION", "contractId": "cct-metro", "contractType": "SELF_TRANSFER", "fromMode": "TRAIN", "toMode": "METRO", "walkingMinutes": 12, "window": {"plannedArrivalAt": now.isoformat().replace("+00:00", "Z"), "nextDepartureAt": (now + timedelta(minutes=40)).isoformat().replace("+00:00", "Z")}}, "corr-test", "cmd-test")
    assert con["window"]["mctMinutes"] == 35
    assert con["walkingDistanceMeters"] == 960
    assert "Transfer from TRAIN" in con["transferInstructions"]


def test_cross_mode_train_to_metro_same_station_does_not_use_same_platform_mct() -> None:
    service = TransferManagementService(InMemoryStore())
    now = datetime(2026, 1, 1, 10, tzinfo=UTC)
    plan = service.create_plan({"itineraryRef": "iti-metro-same", "planningSnapshotVersion": 1, "travelerRefs": ["trav-1"]}, "corr-test", "cmd-test")
    base = {"transferPlanId": plan["transferPlanId"], "itineraryRef": "iti-metro-same", "previousSegmentRef": "seg-train", "nextSegmentRef": "metro-1", "travelerRefs": ["trav-1"], "fromNodeRef": "北京南", "toNodeRef": "北京南", "fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "IN_STATION", "contractId": "cct-metro-same", "contractType": "SELF_TRANSFER", "fromMode": "TRAIN", "toMode": "METRO"}

    with pytest.raises(DomainError) as exc:
        service.register_connection(base | {"window": {"plannedArrivalAt": now.isoformat().replace("+00:00", "Z"), "nextDepartureAt": (now + timedelta(minutes=20)).isoformat().replace("+00:00", "Z")}}, "corr-test", "cmd-test")

    assert "INSUFFICIENT_CONNECTION_TIME" in str(exc.value)
    con = service.register_connection(base | {"nextSegmentRef": "metro-2", "window": {"plannedArrivalAt": now.isoformat().replace("+00:00", "Z"), "nextDepartureAt": (now + timedelta(minutes=40)).isoformat().replace("+00:00", "Z")}}, "corr-test", "cmd-test")
    assert con["window"]["mctMinutes"] == 35


def test_itinerary_proposed_validates_mct_and_publishes_outbox_events() -> None:
    store = InMemoryStore()
    service = TransferManagementService(store)
    now = datetime(2026, 1, 1, 10, tzinfo=UTC)
    envelope = EventEnvelope(
        eventId="evt-itinerary-proposed",
        eventType="ItineraryProposed",
        producer="trip-planning",
        correlationId="corr-test",
        causationId="cmd-test",
        payload={
            "intentRef": "intent-1",
            "itineraries": [
                {
                    "itineraryRef": "iti-proposed",
                    "legs": [
                        {"serviceSegmentRef": "seg-train", "originStopRef": "南京南", "destinationStopRef": "北京南", "departureTime": (now - timedelta(hours=1)).isoformat().replace("+00:00", "Z"), "arrivalTime": now.isoformat().replace("+00:00", "Z"), "mode": "TRAIN"},
                        {"serviceSegmentRef": "metro-1", "originStopRef": "北京南", "destinationStopRef": "西单", "departureTime": (now + timedelta(minutes=20)).isoformat().replace("+00:00", "Z"), "arrivalTime": (now + timedelta(minutes=40)).isoformat().replace("+00:00", "Z"), "mode": "METRO"},
                    ],
                    "transfers": [{"transferCategory": "IN_STATION", "samePlatform": True}],
                }
            ],
            "planningSnapshotRefs": ["snapshot-1"],
        },
    )

    assert service.handle_trip_planning_event(envelope, "events:trip-planning") is True
    assert service.handle_trip_planning_event(envelope, "events:trip-planning") is True

    events = store.take_outbox()
    rejected = [event for event in events if event.eventType == "ConnectionValidationRejected"]
    assert len(rejected) == 1
    assert rejected[0].payload["validation"]["reason"] == "INSUFFICIENT_CONNECTION_TIME"
    assert rejected[0].payload["minimumConnectionTime"]["minutes"] == 35
    assert [event.eventType for event in events].count("ConnectionValidationRejected") == 1
