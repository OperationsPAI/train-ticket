from __future__ import annotations

from collections.abc import Iterable, Mapping
from contextlib import nullcontext
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from hashlib import sha256
from uuid import UUID
from typing import Any

from train_ticket_platform.events import EventEnvelope, canonical_correlation_id, rfc3339_utc
from train_ticket_platform.ids import new_prefixed_uuid7

from transfer_management.domain import (
    ActorRef,
    Connection,
    ConnectionContract,
    ConnectionStatus,
    ConnectionWindow,
    ContractStatus,
    ContractType,
    DomainError,
    MctRule,
    MctRuleStatus,
    NodeType,
    PreconditionFailed,
    RecoveryCaseMapping,
    RecoveryTriggerStatus,
    ReportType,
    RiskEvaluation,
    RiskLevel,
    SegmentStatusReport,
    TransferCategory,
    TransferPlan,
    TransferPlanStatus,
    now_utc,
    optional_dt,
    parse_dt,
    require_text,
)
from transfer_management.downstream import DisruptionRecoveryClient, DownstreamError

PRODUCER = "transfer-management"
PROTECTED_TYPES = {ContractType.PROTECTED, ContractType.SUPPLIER_PROTECTED}


class NotFoundError(KeyError):
    pass


PreconditionFailedError = PreconditionFailed


def folded_uuid7(material: str) -> str:
    digest = bytearray(sha256(material.encode("utf-8")).digest()[:16])
    digest[6] = (digest[6] & 0x0F) | 0x70
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(UUID(bytes=bytes(digest)))


def _event_id(event_type: str, aggregate_id: str, version: int) -> str:
    return f"evt-{folded_uuid7(f'{PRODUCER}:{event_type}:{aggregate_id}:{version}')}"


def _envelope(event_type: str, aggregate_id: str, version: int, payload: Mapping[str, Any], correlation_id: str, causation_id: str, occurred_at: datetime) -> EventEnvelope:
    causation = causation_id or ""
    if causation and not causation.startswith(("cmd-", "evt-")):
        causation = f"cmd-{causation}"
    return EventEnvelope(eventId=_event_id(event_type, aggregate_id, version), eventType=event_type, occurredAt=occurred_at, correlationId=canonical_correlation_id(correlation_id), causationId=causation or None, producer=PRODUCER, schemaVersion=1, payload=payload)


class InMemoryStore:
    def __init__(self) -> None:
        self.plans: dict[str, TransferPlan] = {}
        self.connections: dict[str, Connection] = {}
        self.contracts: dict[str, ConnectionContract] = {}
        self.mct_rules: dict[str, MctRule] = {}
        self.reports: dict[str, SegmentStatusReport] = {}
        self.processed_events: set[str] = set()
        self._outbox: list[EventEnvelope] = []

    def transaction(self) -> Any:
        return nullcontext()

    def unit_of_work(self) -> Any:
        return nullcontext()

    def append_outbox(self, envelopes: Iterable[EventEnvelope]) -> None:
        self._outbox.extend(envelopes)

    def take_outbox(self) -> tuple[EventEnvelope, ...]:
        items = tuple(self._outbox)
        self._outbox.clear()
        return items

    def mark_processed(self, event_id: str, stream: str | None = None) -> bool:
        if event_id in self.processed_events:
            return False
        self.processed_events.add(event_id)
        return True

    def save_plan(self, plan: TransferPlan) -> None:
        self.plans[plan.transferPlanId] = plan

    def get_plan(self, plan_id: str) -> TransferPlan:
        try:
            return self.plans[plan_id]
        except KeyError as exc:
            raise NotFoundError(f"transfer plan not found: {plan_id}") from exc

    def save_connection(self, connection: Connection) -> None:
        self.connections[connection.connectionId] = connection

    def get_connection(self, connection_id: str) -> Connection:
        try:
            return self.connections[connection_id]
        except KeyError as exc:
            raise NotFoundError(f"connection not found: {connection_id}") from exc

    def list_connections_for_plan(self, plan_id: str) -> tuple[Connection, ...]:
        return tuple(c for c in self.connections.values() if c.transferPlanId == plan_id)

    def list_connections_for_journey(self, journey_order_id: str) -> tuple[Connection, ...]:
        return tuple(c for c in self.connections.values() if c.journeyOrderId == journey_order_id)

    def list_connections_for_segment(self, segment_ref: str) -> tuple[Connection, ...]:
        return tuple(c for c in self.connections.values() if c.previousSegmentRef == segment_ref or c.nextSegmentRef == segment_ref)

    def find_connection_by_case_id(self, case_id: str) -> Connection | None:
        for connection in self.connections.values():
            if connection.recovery and case_id in connection.recovery.caseIds:
                return connection
        return None

    def save_contract(self, contract: ConnectionContract) -> None:
        self.contracts[contract.connectionContractId] = contract

    def get_contract(self, contract_id: str) -> ConnectionContract:
        try:
            return self.contracts[contract_id]
        except KeyError as exc:
            raise NotFoundError(f"connection contract not found: {contract_id}") from exc

    def save_mct_rule(self, rule: MctRule) -> None:
        self.mct_rules[rule.mctRuleId] = rule

    def get_mct_rule(self, rule_id: str) -> MctRule:
        try:
            return self.mct_rules[rule_id]
        except KeyError as exc:
            raise NotFoundError(f"MCT rule not found: {rule_id}") from exc

    def list_mct_rules(self, **filters: Any) -> tuple[MctRule, ...]:
        items = list(self.mct_rules.values())
        for key, value in filters.items():
            if value is None:
                continue
            filtered: list[MctRule] = []
            for rule in items:
                field = getattr(rule, key)
                if (field.value if hasattr(field, "value") else field) == value:
                    filtered.append(rule)
            items = filtered
        return tuple(sorted(items, key=lambda r: (r.mctRuleId, r.version)))

    def save_report(self, report: SegmentStatusReport) -> None:
        self.reports[report.segmentStatusReportId] = report


class TransferManagementService:
    def __init__(self, store: Any, downstream: DisruptionRecoveryClient | None = None) -> None:
        self.store = store
        self.downstream = downstream or DisruptionRecoveryClient()

    def transaction(self) -> Any:
        transaction = getattr(self.store, "transaction", None)
        return transaction() if callable(transaction) else nullcontext()

    def _append(self, events: list[EventEnvelope]) -> None:
        append = getattr(self.store, "append_outbox", None)
        if callable(append):
            append(tuple(events))

    def create_plan(self, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        plan = TransferPlan(new_prefixed_uuid7("tpl"), require_text(data.get("itineraryRef"), "itineraryRef"), int(data.get("planningSnapshotVersion") or 0), str(data.get("journeyOrderId") or "").strip() or None, tuple(str(x) for x in data.get("travelerRefs") or []), TransferPlanStatus.DRAFT, (), 0, at, at, optional_dt(data.get("expiresAt")))
        if plan.planningSnapshotVersion <= 0:
            raise DomainError("planningSnapshotVersion must be positive")
        if not plan.travelerRefs:
            raise DomainError("travelerRefs must be non-empty")
        self.store.save_plan(plan)
        self._append([_envelope("TransferPlanCreated", plan.transferPlanId, 1, {k: v for k, v in plan.to_json(()).items() if k != "connections"} | {"travelerRefs": list(plan.travelerRefs)}, correlation_id, causation_id, at)])
        return self._plan_json(plan)

    def evaluate_plan(self, plan_id: str, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = optional_dt(data.get("asOf")) or now_utc()
        plan = self.store.get_plan(plan_id)
        connections = self.store.list_connections_for_plan(plan_id)
        plan = plan.evaluated(int(data.get("planningSnapshotVersion") or plan.planningSnapshotVersion), tuple(c.connectionId for c in connections), at, False)
        self.store.save_plan(plan)
        events = [_envelope("TransferPlanEvaluated", plan.transferPlanId, plan.version + 1, {"transferPlanId": plan.transferPlanId, "itineraryRef": plan.itineraryRef, "planningSnapshotVersion": plan.planningSnapshotVersion, "status": plan.status.value, "evaluationVersion": plan.evaluationVersion, "connectionIds": list(plan.connections), "riskPolicyVersion": plan.riskPolicyVersion, "evaluatedAt": rfc3339_utc(at)} | ({"journeyOrderId": plan.journeyOrderId} if plan.journeyOrderId else {}), correlation_id, causation_id, at)]
        for connection in connections:
            updated, risk_events = self._refresh_connection(connection, at, correlation_id, causation_id, None)
            self.store.save_connection(updated)
            events.extend(risk_events)
        self._append(events)
        return self._plan_json(plan)

    def expire_plan(self, plan_id: str, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        plan = self.store.get_plan(plan_id)
        previous = plan.status
        plan = plan.transition(TransferPlanStatus.EXPIRED, at)
        self.store.save_plan(plan)
        self._append([_envelope("TransferPlanExpired", plan.transferPlanId, plan.version + 1, {"transferPlanId": plan.transferPlanId, "itineraryRef": plan.itineraryRef, "previousStatus": previous.value, "status": "EXPIRED", "expiredAt": rfc3339_utc(at)} | ({"reason": str(data.get("reason"))} if data.get("reason") else {}), correlation_id, causation_id, at)])
        return self._plan_json(plan)

    def get_plan(self, plan_id: str) -> dict[str, Any]:
        return self._plan_json(self.store.get_plan(plan_id))

    def register_connection(self, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        plan = self.store.get_plan(require_text(data.get("transferPlanId"), "transferPlanId"))
        rule = self._published_rule(NodeType(str(data.get("fromNodeType"))), NodeType(str(data.get("toNodeType"))), TransferCategory(str(data.get("transferCategory"))), at)
        window_data = dict(data.get("window") or {})
        planned = parse_dt(window_data.get("plannedArrivalAt"), "window.plannedArrivalAt")
        departure = parse_dt(window_data.get("nextDepartureAt"), "window.nextDepartureAt")
        cutoff = parse_dt(window_data.get("nextCutoffAt") or window_data.get("nextDepartureAt"), "window.nextCutoffAt")
        window = ConnectionWindow.build(planned, departure, cutoff, int(window_data.get("mctMinutes") or rule.minimumMinutes), optional_dt(window_data.get("actualArrivalAt")))
        evaluation = self._evaluate_window(window, rule, at, new_prefixed_uuid7("tre"), ())
        contract_id = require_text(data.get("contractId"), "contractId")
        contract_type = ContractType(str(data.get("contractType")))
        status = ConnectionStatus.FEASIBLE if evaluation.riskLevel is RiskLevel.FEASIBLE else ConnectionStatus.TIGHT if evaluation.riskLevel is RiskLevel.TIGHT else ConnectionStatus.AT_RISK
        connection = Connection(new_prefixed_uuid7("con"), plan.transferPlanId, require_text(data.get("itineraryRef"), "itineraryRef"), require_text(data.get("previousSegmentRef"), "previousSegmentRef"), require_text(data.get("nextSegmentRef"), "nextSegmentRef"), tuple(str(x) for x in data.get("travelerRefs") or []), require_text(data.get("fromNodeRef"), "fromNodeRef"), require_text(data.get("toNodeRef"), "toNodeRef"), NodeType(str(data.get("fromNodeType"))), NodeType(str(data.get("toNodeType"))), TransferCategory(str(data.get("transferCategory"))), contract_id, contract_type, ConnectionStatus.PLANNED, evaluation, window, at, at, str(data.get("journeyOrderId") or plan.journeyOrderId or "").strip() or None, serviceDate=str(data.get("serviceDate") or cutoff.date().isoformat()), scheduledServiceRef=str(data.get("scheduledServiceRef") or "").strip() or None)
        connection = connection.transition(status, at)
        plan = plan.add_connection(connection.connectionId, at)
        self.store.save_plan(plan)
        self.store.save_connection(connection)
        events = [
            _envelope("ConnectionRegistered", connection.connectionId, 1, connection.to_json() | {"registeredAt": rfc3339_utc(at)}, correlation_id, causation_id, at),
            self._risk_event(connection, None, correlation_id, causation_id, at, None),
        ]
        self._append(events)
        return connection.to_json()

    def get_connection(self, connection_id: str) -> dict[str, Any]:
        return self.store.get_connection(connection_id).to_json()

    def list_connections_by_journey(self, journey_order_id: str) -> dict[str, Any]:
        items = [c.to_json() for c in self.store.list_connections_for_journey(journey_order_id)]
        return {"items": items, "total": len(items), "limit": len(items), "offset": 0}

    def report_segment_status(self, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        with self.transaction():
            at = now_utc()
            report = SegmentStatusReport(new_prefixed_uuid7("tsr"), require_text(data.get("segmentRef"), "segmentRef"), ReportType(str(data.get("reportType"))), ActorRef(**dict(data.get("reportedBy") or {})), require_text(data.get("sourceSystem"), "sourceSystem"), require_text(data.get("sourceRecordId"), "sourceRecordId"), parse_dt(data.get("observedAt"), "observedAt"), optional_dt(data.get("estimatedArrivalAt")), optional_dt(data.get("actualArrivalAt")), optional_dt(data.get("cancelledAt")), str(data.get("reason") or "").strip() or None)
            self.store.save_report(report)
            updated: list[Connection] = []
            events: list[EventEnvelope] = []
            for connection in self.store.list_connections_for_segment(report.segmentRef):
                new_connection, risk_events = self._refresh_connection(connection, at, correlation_id, causation_id, report)
                self.store.save_connection(new_connection)
                updated.append(new_connection)
                events.extend(risk_events)
            self._append(events)
            return {"report": report.to_json(), "updatedConnections": [c.to_json() for c in updated]}

    def propose_contract(self, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        connection_id = require_text(data.get("connectionId"), "connectionId")
        self.store.get_connection(connection_id)
        contract = ConnectionContract(new_prefixed_uuid7("cct"), connection_id, ContractType(str(data.get("contractType"))), ContractStatus.PROPOSED, require_text(data.get("responsibleParty"), "responsibleParty"), require_text(data.get("coverageSummary"), "coverageSummary"), require_text(data.get("disclosureVersion"), "disclosureVersion"), at, at, str(data.get("termsSnapshotRef") or "").strip() or None)
        self.store.save_contract(contract)
        self._append([_envelope("ConnectionContractProposed", contract.connectionContractId, 1, contract.to_json() | {"proposedAt": rfc3339_utc(at)}, correlation_id, causation_id, at)])
        return contract.to_json()

    def confirm_contract(self, contract_id: str, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = optional_dt(data.get("confirmedAt")) or now_utc()
        contract = self.store.get_contract(contract_id)
        previous = contract.status
        if contract.status is ContractStatus.PROPOSED:
            contract = contract.transition(ContractStatus.ELIGIBLE, at)
        contract = contract.transition(ContractStatus.CONFIRMED, at)
        self.store.save_contract(contract)
        self._append([_envelope("ConnectionContractConfirmed", contract.connectionContractId, contract.version + 1, {"connectionContractId": contract.connectionContractId, "connectionId": contract.connectionId, "contractType": contract.contractType.value, "previousStatus": previous.value, "status": "CONFIRMED", "acceptedByRef": require_text(data.get("acceptedByRef"), "acceptedByRef"), "acceptedVersion": require_text(data.get("acceptedVersion"), "acceptedVersion"), "confirmedAt": rfc3339_utc(at)}, correlation_id, causation_id, at)])
        return contract.to_json()

    def withdraw_contract(self, contract_id: str, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        contract = self.store.get_contract(contract_id)
        previous = contract.status
        target = ContractStatus.REJECTED if contract.status is ContractStatus.PROPOSED else ContractStatus.VOIDED
        contract = contract.transition(target, at)
        self.store.save_contract(contract)
        actor = ActorRef(**dict(data.get("withdrawnBy") or {}))
        self._append([_envelope("ConnectionContractWithdrawn", contract.connectionContractId, contract.version + 1, {"connectionContractId": contract.connectionContractId, "connectionId": contract.connectionId, "contractType": contract.contractType.value, "previousStatus": previous.value, "status": contract.status.value, "withdrawnBy": actor.to_json(), "reason": require_text(data.get("reason"), "reason"), "withdrawnAt": rfc3339_utc(at)}, correlation_id, causation_id, at)])
        return contract.to_json()

    def create_mct_rule(self, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        rule = MctRule(new_prefixed_uuid7("mct"), 1, MctRuleStatus.DRAFT, NodeType(str(data.get("fromNodeType"))), NodeType(str(data.get("toNodeType"))), TransferCategory(str(data.get("transferCategory"))), int(data.get("minimumMinutes") or 0), dict(data.get("conditions") or {}), parse_dt(data.get("validFrom"), "validFrom"), optional_dt(data.get("validUntil")))
        self.store.save_mct_rule(rule)
        self._append([_envelope("MctRuleCreated", rule.mctRuleId, rule.version, rule.to_json() | {"createdAt": rfc3339_utc(at)}, correlation_id, causation_id, at)])
        return rule.to_json()

    def update_mct_rule(self, rule_id: str, data: Mapping[str, Any]) -> dict[str, Any]:
        rule = self.store.get_mct_rule(rule_id).update(data)
        self.store.save_mct_rule(rule)
        return rule.to_json()

    def publish_mct_rule(self, rule_id: str, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        rule = self.store.get_mct_rule(rule_id)
        previous = rule.status
        rule = rule.publish(at)
        self.store.save_mct_rule(rule)
        actor = ActorRef(**dict(data.get("publishedBy") or {}))
        payload = rule.to_json() | {"previousStatus": previous.value, "publishedBy": actor.to_json(), "publishedAt": rfc3339_utc(at)}
        self._append([_envelope("MctRulePublished", rule.mctRuleId, rule.version, payload, correlation_id, causation_id, at)])
        return rule.to_json()

    def retire_mct_rule(self, rule_id: str, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = optional_dt(data.get("retiredAt")) or now_utc()
        rule = self.store.get_mct_rule(rule_id)
        previous = rule.status
        rule = rule.retire(at)
        self.store.save_mct_rule(rule)
        actor = ActorRef(**dict(data.get("retiredBy") or {}))
        self._append([_envelope("MctRuleRetired", rule.mctRuleId, rule.version, {"mctRuleId": rule.mctRuleId, "version": rule.version, "previousStatus": previous.value, "status": "RETIRED", "retiredBy": actor.to_json(), "retireReason": require_text(data.get("retireReason"), "retireReason"), "retiredAt": rfc3339_utc(at)}, correlation_id, causation_id, at)])
        return rule.to_json()

    def list_mct_rules(self, filters: Mapping[str, Any]) -> dict[str, Any]:
        items = list(self.store.mct_rules.values()) if hasattr(self.store, "mct_rules") else list(self.store.list_mct_rules())
        for key in ("fromNodeType", "toNodeType", "transferCategory", "status"):
            value = filters.get(key)
            if value:
                items = [r for r in items if getattr(r, key).value == value]
        total = len(items)
        limit = max(1, min(int(filters.get("limit") or 20), 100))
        offset = max(0, int(filters.get("offset") or 0))
        return {"items": [r.to_json() for r in items[offset:offset + limit]], "total": total, "limit": limit, "offset": offset}

    def handle_recovery_event(self, envelope: EventEnvelope, stream: str | None = None) -> bool:
        if envelope.eventType not in {"RecoveryCompleted", "RecoveryFailed"}:
            return False
        with self.transaction():
            mark = getattr(self.store, "mark_processed", None)
            if callable(mark) and not mark(envelope.eventId, stream):
                return True
            case_id = str(envelope.payload.get("caseId") or "")
            connection = self.store.find_connection_by_case_id(case_id)
            if connection is None:
                return True
            at = now_utc()
            if envelope.eventType == "RecoveryCompleted":
                previous = connection.status
                connection = replace(connection, latestEvaluation=replace(connection.latestEvaluation, riskLevel=RiskLevel.RECOVERED, evaluatedAt=at), updatedAt=at)
                connection = connection.transition(ConnectionStatus.RECOVERED, at) if connection.status != ConnectionStatus.RECOVERED else connection
                event = _envelope("ConnectionRecovered", connection.connectionId, connection.version + 1, {"connection": connection.ref_json(), "previousStatus": previous.value, "status": "RECOVERED", "riskLevel": "RECOVERED", "recoveryCaseId": case_id, "disruptionRecoveryEventId": envelope.eventId, "recoveredAt": rfc3339_utc(at), "recoverySummary": "Disruption recovery completed"}, envelope.correlationId, envelope.eventId, at)
            else:
                reason = str(envelope.payload.get("reason") or "Recovery failed")[:500]
                event = _envelope("ConnectionRecoveryFailed", connection.connectionId, connection.version + 1, {"connection": connection.ref_json(), "status": connection.status.value, "recoveryCaseId": case_id, "disruptionRecoveryEventId": envelope.eventId, "failedAt": rfc3339_utc(at), "reason": reason}, envelope.correlationId, envelope.eventId, at)
            self.store.save_connection(connection)
            self._append([event])
            return True

    def _plan_json(self, plan: TransferPlan) -> dict[str, Any]:
        connections = tuple(c.summary_json() for c in self.store.list_connections_for_plan(plan.transferPlanId))
        return plan.to_json(connections)

    def _published_rule(self, from_type: NodeType, to_type: NodeType, category: TransferCategory, at: datetime) -> MctRule:
        items = list(getattr(self.store, "mct_rules", {}).values()) if hasattr(self.store, "mct_rules") else list(self.store.list_mct_rules())
        for rule in items:
            if rule.matches(from_type, to_type, category, at):
                return rule
        return MctRule("mct-builtin", 1, MctRuleStatus.PUBLISHED, from_type, to_type, category, 20, {"builtin": True}, datetime(1970, 1, 1, tzinfo=UTC), None, datetime(1970, 1, 1, tzinfo=UTC))

    def _evaluate_window(self, window: ConnectionWindow, rule: MctRule, at: datetime, evaluation_id: str, extra_reasons: Iterable[str]) -> RiskEvaluation:
        reasons = list(extra_reasons)
        if window.availableMinutes < 0 or "NEXT_SEGMENT_CANCELLED" in reasons or "PREVIOUS_SEGMENT_CANCELLED" in reasons:
            level = RiskLevel.MISSED
            if window.availableMinutes < 0:
                reasons.append("CUTOFF_EXPIRED")
        elif window.bufferMinutes < 0:
            level = RiskLevel.AT_RISK
            reasons.append("INSUFFICIENT_BUFFER")
        elif window.bufferMinutes < 10:
            level = RiskLevel.TIGHT
            reasons.append("LOW_BUFFER")
        else:
            level = RiskLevel.FEASIBLE
        return RiskEvaluation(evaluation_id, level, rule.mctRuleId, rule.version, window.availableMinutes, rule.minimumMinutes, tuple(dict.fromkeys(reasons)), at)

    def _refresh_connection(self, connection: Connection, at: datetime, correlation_id: str, causation_id: str, report: SegmentStatusReport | None) -> tuple[Connection, list[EventEnvelope]]:
        previous_status = connection.status
        window = connection.window
        reasons: list[str] = []
        if report:
            if report.segmentRef == connection.previousSegmentRef:
                if report.reportType is ReportType.DELAY and report.estimatedArrivalAt:
                    window = window.with_arrival(report.estimatedArrivalAt)
                    reasons.append("PREVIOUS_SEGMENT_DELAYED")
                elif report.reportType is ReportType.ARRIVAL and report.actualArrivalAt:
                    window = window.with_arrival(report.actualArrivalAt)
                elif report.reportType is ReportType.CANCELLED:
                    reasons.append("PREVIOUS_SEGMENT_CANCELLED")
            elif report.segmentRef == connection.nextSegmentRef and report.reportType is ReportType.CANCELLED:
                reasons.append("NEXT_SEGMENT_CANCELLED")
        rule = self._published_rule(connection.fromNodeType, connection.toNodeType, connection.transferCategory, at)
        evaluation = self._evaluate_window(window, rule, at, new_prefixed_uuid7("tre"), reasons)
        updated = connection.apply_evaluation(evaluation, window, at)
        events = [self._risk_event(updated, previous_status if previous_status != updated.status else None, correlation_id, causation_id, at, report.segmentStatusReportId if report else None)]
        if updated.status is ConnectionStatus.AT_RISK and previous_status != ConnectionStatus.AT_RISK:
            events.append(_envelope("TransferAtRisk", updated.connectionId, updated.version + 2, {"connection": updated.ref_json(), "previousStatus": previous_status.value, "status": "AT_RISK", "riskLevel": "AT_RISK", "riskPolicyVersion": "builtin-v1", "reasons": list(evaluation.reasons), "window": updated.window.to_json(), "detectedAt": rfc3339_utc(at)}, correlation_id, causation_id, at))
        if updated.status is ConnectionStatus.MISSED and previous_status != ConnectionStatus.MISSED:
            cause = "OPS_DECLARED"
            if "PREVIOUS_SEGMENT_CANCELLED" in evaluation.reasons:
                cause = "PREVIOUS_SEGMENT_CANCELLED"
            elif "NEXT_SEGMENT_CANCELLED" in evaluation.reasons:
                cause = "NEXT_SEGMENT_CANCELLED"
            elif "PREVIOUS_SEGMENT_DELAYED" in evaluation.reasons:
                cause = "PREVIOUS_SEGMENT_DELAYED"
            elif "CUTOFF_EXPIRED" in evaluation.reasons:
                cause = "CUTOFF_EXPIRED"
            recovery_required = updated.contractType in PROTECTED_TYPES
            if recovery_required:
                missed_at = rfc3339_utc(at)
                key = folded_uuid7(f"{updated.connectionId}:{missed_at}:{updated.version}:{updated.journeyOrderId}")
                updated = updated.with_recovery(RecoveryCaseMapping(RecoveryTriggerStatus.PENDING, key), at)
            missed_event = _envelope("ConnectionMissed", updated.connectionId, updated.version + 3, {"connection": updated.ref_json(), "previousStatus": previous_status.value, "status": "MISSED", "riskLevel": "MISSED", "contractType": updated.contractType.value, "missedAt": rfc3339_utc(at), "missedCause": cause, "window": updated.window.to_json(), "recoveryRequired": recovery_required} | ({"recovery": updated.recovery.to_json()} if updated.recovery else {}), correlation_id, causation_id, at)
            events.append(missed_event)
            if recovery_required:
                updated, recovery_event = self._open_recovery(updated, missed_event.eventId, at, correlation_id, causation_id)
                if recovery_event:
                    events.append(recovery_event)
        return updated, events

    def _open_recovery(self, connection: Connection, evidence_id: str, at: datetime, correlation_id: str, causation_id: str) -> tuple[Connection, EventEnvelope | None]:
        if not connection.recovery:
            return connection, None
        if not connection.journeyOrderId:
            recovery = replace(connection.recovery, recoveryTriggerStatus=RecoveryTriggerStatus.FAILED, failureReason="journeyOrderId is required")
            return connection.with_recovery(recovery, at), None
        body = {"disruptionType": "MISSED_CONNECTION", "segmentRef": connection.nextSegmentRef, "serviceDate": connection.serviceDate or at.date().isoformat(), "evidence": {"evidenceRef": connection.connectionId, "sourceSystem": "TRANSFER_MANAGEMENT", "sourceRecordId": evidence_id, "summary": "Protected transfer connection missed", "occurredAt": rfc3339_utc(at)}, "affectedOrderIds": [connection.journeyOrderId], "reportedBy": {"actorType": "SYSTEM", "actorId": "transfer-management"}, "autoRecovery": "WAIT"}
        if connection.scheduledServiceRef:
            body["scheduledServiceRef"] = connection.scheduledServiceRef
        try:
            response = self.downstream.report_missed_connection(body, connection.recovery.outboundIdempotencyKey, canonical_correlation_id(correlation_id))
        except DownstreamError as exc:
            recovery = replace(connection.recovery, recoveryTriggerStatus=RecoveryTriggerStatus.FAILED, failureReason=str(exc)[:500])
            return connection.with_recovery(recovery, at), _envelope("ConnectionRecoveryFailed", connection.connectionId, connection.version + 4, {"connection": connection.ref_json(), "status": "MISSED", "failedAt": rfc3339_utc(at), "reason": str(exc)[:500]}, correlation_id, causation_id, at)
        disruption = dict(response.get("disruption") or {})
        incident = dict(response.get("incident") or {})
        cases = tuple(str(c.get("caseId")) for c in response.get("recoveryCases") or [] if isinstance(c, Mapping) and c.get("caseId"))
        recovery = replace(connection.recovery, recoveryTriggerStatus=RecoveryTriggerStatus.OPENED, disruptionId=str(disruption.get("disruptionId") or "") or None, incidentId=str(incident.get("incidentId") or "") or None, caseIds=cases, openedAt=at)
        return connection.with_recovery(recovery, at), None

    def _risk_event(self, connection: Connection, previous: ConnectionStatus | None, correlation_id: str, causation_id: str, at: datetime, report_id: str | None) -> EventEnvelope:
        payload = {"connection": connection.ref_json(), "status": connection.status.value, "evaluation": connection.latestEvaluation.to_json(), "window": connection.window.to_json()}
        if previous:
            payload["previousStatus"] = previous.value
        if report_id:
            payload["sourceReportId"] = report_id
        return _envelope("TransferRiskEvaluated", connection.connectionId, connection.version + 1, payload, correlation_id, causation_id, at)
