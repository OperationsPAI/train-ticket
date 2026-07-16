from __future__ import annotations

from collections.abc import Iterable, Mapping
from contextlib import nullcontext
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from hashlib import sha256
from uuid import UUID
import logging
from typing import Any, NoReturn

from train_ticket_platform.events import EventEnvelope, canonical_correlation_id, rfc3339_utc
from train_ticket_platform.ids import new_prefixed_uuid7

from transfer_management.domain import (
    ActorRef,
    Connection,
    ConnectionGuarantee,
    ConnectionValidator,
    CrossModeTransfer,
    ConnectionContract,
    ConnectionStatus,
    ConnectionWindow,
    ContractStatus,
    ContractType,
    DomainError,
    MinimumConnectionTime,
    MissedConnectionDetector,
    MctRule,
    MctRuleStatus,
    NodeType,
    PreconditionFailed,
    RecoveryCaseMapping,
    RebookingRequest,
    RebookingSuggestion,
    RecoveryTriggerStatus,
    ReportType,
    RiskEvaluation,
    RiskLevel,
    RiskPolicyStatus,
    RiskThresholds,
    SegmentStatusReport,
    TransferCategory,
    TransferMode,
    TransferPlan,
    TransferProposal,
    TransferRiskPolicy,
    TransferPlanStatus,
    now_utc,
    optional_dt,
    parse_dt,
    require_text,
)
from transfer_management.downstream import DisruptionRecoveryClient, DownstreamError
from transfer_management.topology import PlaceNetworkClient, PlaceNetworkUnavailable, PlaceNetworkValidationError, TopologySnapshot

PRODUCER = "transfer-management"
PROTECTED_TYPES = {ContractType.PROTECTED, ContractType.SUPPLIER_PROTECTED}
FULFILLMENT_SEGMENT_EVENT_TYPES = {"SegmentArrived", "SegmentDelayed", "SegmentCancelled"}
DISRUPTION_SEGMENT_EVENT_TYPES = {"TrainDelayed", "TrainCancelled"}
AUTO_REBOOKING_WINDOW = timedelta(hours=4)
LOGGER = logging.getLogger(__name__)


class NotFoundError(KeyError):
    pass


PreconditionFailedError = PreconditionFailed


class ValidationFailedError(ValueError):
    pass


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
        self.risk_policies: dict[str, TransferRiskPolicy] = {}
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

    def save_risk_policy(self, policy: TransferRiskPolicy) -> None:
        self.risk_policies[policy.riskPolicyId] = policy

    def get_risk_policy(self, policy_id: str) -> TransferRiskPolicy:
        try:
            return self.risk_policies[policy_id]
        except KeyError as exc:
            raise NotFoundError(f"risk policy not found: {policy_id}") from exc

    def list_risk_policies(self) -> tuple[TransferRiskPolicy, ...]:
        return tuple(sorted(self.risk_policies.values(), key=lambda p: (p.createdAt, p.riskPolicyId)))

    def get_active_risk_policy(self) -> TransferRiskPolicy | None:
        active = [p for p in self.risk_policies.values() if p.status is RiskPolicyStatus.ACTIVE]
        if not active:
            return None
        return max(active, key=lambda p: (p.activatedAt or p.createdAt, p.riskPolicyId))

    def find_report_by_source_key(self, source_key: str) -> SegmentStatusReport | None:
        return self.reports.get(source_key)

    def save_report(self, report: SegmentStatusReport) -> bool:
        key = segment_report_source_key(report)
        if key in self.reports:
            return False
        self.reports[key] = report
        return True


def segment_report_source_key(report: SegmentStatusReport) -> str:
    return "\u001f".join((report.sourceSystem, report.sourceRecordId, report.segmentRef, report.reportType.value, rfc3339_utc(report.observedAt)))


def _replacement_window_json(window: Mapping[str, Any]) -> dict[str, Any]:
    data = {
        "plannedArrivalAt": rfc3339_utc(parse_dt(window.get("plannedArrivalAt"), "replacementWindow.plannedArrivalAt")),
        "nextDepartureAt": rfc3339_utc(parse_dt(window.get("nextDepartureAt"), "replacementWindow.nextDepartureAt")),
        "source": require_text(window.get("source"), "replacementWindow.source"),
    }
    if data["source"] not in {"OPERATIONS", "SYSTEM"}:
        raise DomainError("replacementWindow.source is invalid")
    cutoff = window.get("nextCutoffAt") or window.get("nextDepartureAt")
    data["nextCutoffAt"] = rfc3339_utc(parse_dt(cutoff, "replacementWindow.nextCutoffAt"))
    if parse_dt(data["nextDepartureAt"], "replacementWindow.nextDepartureAt") <= parse_dt(data["plannedArrivalAt"], "replacementWindow.plannedArrivalAt"):
        raise DomainError("replacementWindow.nextDepartureAt must be after plannedArrivalAt")
    return data



def _mode(value: Any, default: TransferMode = TransferMode.TRAIN) -> TransferMode:
    return TransferMode(str(value or default.value).upper())


def _same_platform(data: Mapping[str, Any], category: TransferCategory) -> bool:
    if data.get("samePlatform") is not None:
        return bool(data.get("samePlatform"))
    return category is TransferCategory.IN_STATION


def _cross_mode_from_data(data: Mapping[str, Any], from_mode: TransferMode, to_mode: TransferMode, from_node_ref: str, to_node_ref: str) -> CrossModeTransfer:
    walking = int(data.get("walkingMinutes") if data.get("walkingMinutes") is not None else (0 if from_node_ref == to_node_ref else 10))
    override = data.get("mctOverride")
    return CrossModeTransfer(from_mode, to_mode, from_node_ref, to_node_ref, walking, int(override) if override is not None else None)


def _can_use_builtin_mct(data: Mapping[str, Any], from_mode: TransferMode, to_mode: TransferMode, station_ref: str, same_platform: bool) -> bool:
    return (
        from_mode is not to_mode
        or same_platform
        or data.get("stationSize") == "SMALL_STATION"
        or station_ref in {"北京南", "上海虹桥", "广州南", "南京南", "武汉", "成都东"}
        or data.get("mctOverride") is not None
    )


def _topology_weighted_mct_minutes(topology: TopologySnapshot | None) -> int | None:
    if topology is None:
        return None
    return topology.mctAccessTimeMinutes


def _leg_ref(leg: Mapping[str, Any], index: int) -> str:
    return str(leg.get("serviceSegmentRef") or leg.get("segmentRef") or leg.get("servicePlanRef") or f"leg-{index}")


def _itinerary_transfer_hint(itinerary: Mapping[str, Any], index: int) -> Mapping[str, Any]:
    transfers = itinerary.get("transfers") or itinerary.get("transferHints") or ()
    if isinstance(transfers, Iterable) and not isinstance(transfers, (str, bytes, Mapping)):
        items = tuple(item for item in transfers if isinstance(item, Mapping))
        if index - 1 < len(items):
            return items[index - 1]
    return {}


def _optional_int(value: Any) -> int | None:
    return int(value) if value is not None else None


def segment_status_report_from_fulfillment_event(envelope: EventEnvelope) -> dict[str, Any]:
    payload = dict(envelope.payload or {})
    event_type = envelope.eventType
    report_type = {"SegmentDelayed": "DELAY", "SegmentArrived": "ARRIVAL", "SegmentCancelled": "CANCELLED"}[event_type]
    observed_at = payload.get("observedAt") or envelope.occurredAt
    data: dict[str, Any] = {
        "segmentRef": require_text(payload.get("segmentRef"), "payload.segmentRef"),
        "reportType": report_type,
        "reportedBy": {"actorType": "SYSTEM", "actorId": "fulfillment"},
        "sourceSystem": "FULFILLMENT-EVENT",
        "sourceRecordId": envelope.eventId,
        "observedAt": rfc3339_utc(parse_dt(observed_at, "observedAt")),
    }
    if event_type == "SegmentDelayed":
        data["estimatedArrivalAt"] = rfc3339_utc(parse_dt(payload.get("estimatedArrivalAt"), "estimatedArrivalAt"))
    elif event_type == "SegmentArrived":
        data["actualArrivalAt"] = rfc3339_utc(parse_dt(payload.get("arrivedAt"), "arrivedAt"))
    else:
        data["cancelledAt"] = rfc3339_utc(parse_dt(payload.get("cancelledAt"), "cancelledAt"))
    return data


def segment_status_report_from_disruption_event(envelope: EventEnvelope) -> dict[str, Any]:
    payload = dict(envelope.payload or {})
    event_type = envelope.eventType
    observed_at = payload.get("observedAt") or envelope.occurredAt
    data: dict[str, Any] = {
        "segmentRef": require_text(payload.get("segmentRef") or payload.get("serviceRef") or payload.get("trainRef"), "payload.segmentRef"),
        "reportType": "DELAY" if event_type == "TrainDelayed" else "CANCELLED",
        "reportedBy": {"actorType": "SYSTEM", "actorId": "disruption-recovery"},
        "sourceSystem": "FULFILLMENT-EVENT",
        "sourceRecordId": envelope.eventId,
        "observedAt": rfc3339_utc(parse_dt(observed_at, "observedAt")),
    }
    if event_type == "TrainDelayed":
        estimated = payload.get("actualArrivalAt") or payload.get("estimatedArrivalAt") or payload.get("estimatedNewArrival") or payload.get("estimatedNewDeparture")
        if estimated is None and payload.get("delayMinutes") is not None:
            estimated = parse_dt(observed_at, "observedAt") + timedelta(minutes=int(payload.get("delayMinutes") or 0))
        data["estimatedArrivalAt"] = rfc3339_utc(parse_dt(estimated, "estimatedArrivalAt"))
    else:
        data["cancelledAt"] = rfc3339_utc(parse_dt(payload.get("cancelledAt") or observed_at, "cancelledAt"))
    for key in ("rebookingSuggestions", "alternativeConnections"):
        if payload.get(key):
            data["rebookingSuggestions"] = payload[key]
            break
    return data



class TransferManagementService:
    def __init__(self, store: Any, downstream: DisruptionRecoveryClient | None = None, topology: PlaceNetworkClient | None = None) -> None:
        self.store = store
        self.downstream = downstream or DisruptionRecoveryClient()
        self.topology = topology or PlaceNetworkClient("")

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
        unserviceable = False
        reasons: list[str] = []
        for connection in connections:
            if self._find_published_rule(connection.fromNodeType, connection.toNodeType, connection.transferCategory, at) is None:
                unserviceable = True
                reasons.append("NO_PUBLISHED_MCT_RULE")
                break
        plan = plan.evaluated(int(data.get("planningSnapshotVersion") or plan.planningSnapshotVersion), tuple(c.connectionId for c in connections), at, unserviceable)
        active_policy = self._active_risk_policy()
        plan = replace(plan, riskPolicyVersion=active_policy.version if active_policy else "builtin-v1")
        self.store.save_plan(plan)
        payload = {"transferPlanId": plan.transferPlanId, "itineraryRef": plan.itineraryRef, "planningSnapshotVersion": plan.planningSnapshotVersion, "status": plan.status.value, "evaluationVersion": plan.evaluationVersion, "connectionIds": list(plan.connections), "riskPolicyVersion": plan.riskPolicyVersion, "evaluatedAt": rfc3339_utc(at)} | ({"journeyOrderId": plan.journeyOrderId} if plan.journeyOrderId else {})
        if reasons:
            payload["unserviceableReasons"] = reasons
        events = [_envelope("TransferPlanEvaluated", plan.transferPlanId, plan.version + 1, payload, correlation_id, causation_id, at)]
        if unserviceable:
            self._append(events)
            return self._plan_json(plan)
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
        from_node_type = NodeType(str(data.get("fromNodeType")))
        to_node_type = NodeType(str(data.get("toNodeType")))
        from_node_ref = require_text(data.get("fromNodeRef"), "fromNodeRef")
        to_node_ref = require_text(data.get("toNodeRef"), "toNodeRef")
        degraded_reasons: tuple[str, ...] = ()
        try:
            topology = self.topology.validate_connection_nodes(from_node_ref, to_node_ref, from_node_type, to_node_type)
        except PlaceNetworkValidationError as exc:
            raise ValidationFailedError(str(exc)) from exc
        except PlaceNetworkUnavailable:
            topology = None
            degraded_reasons = ("PLACE_NETWORK_UNAVAILABLE",)
        category = TransferCategory(str(data.get("transferCategory")))
        from_mode = _mode(data.get("fromMode"))
        to_mode = _mode(data.get("toMode"))
        cross_mode = _cross_mode_from_data(data, from_mode, to_mode, from_node_ref, to_node_ref)
        override = data.get("mctOverride")
        same_platform = _same_platform(data, category)
        topology_minutes = _topology_weighted_mct_minutes(topology)
        mct = MinimumConnectionTime.default_for(to_node_ref, from_mode, to_mode, same_platform, int(override) if override is not None else topology_minutes)
        rule = self._find_published_rule(from_node_type, to_node_type, category, at)
        if rule is None:
            if topology_minutes is None and not _can_use_builtin_mct(data, from_mode, to_mode, to_node_ref, same_platform):
                raise DomainError("NO_PUBLISHED_MCT_RULE")
            rule = self._default_mct_rule(from_node_type, to_node_type, category, mct, at, "PLACE_NETWORK_ACCESS_TIME" if topology_minutes is not None else "BUILTIN_STATION_POLICY")
        window_data = dict(data.get("window") or {})
        planned = parse_dt(window_data.get("plannedArrivalAt"), "window.plannedArrivalAt")
        departure = parse_dt(window_data.get("nextDepartureAt"), "window.nextDepartureAt")
        cutoff = parse_dt(window_data.get("nextCutoffAt") or window_data.get("nextDepartureAt"), "window.nextCutoffAt")
        requested_mct = int(window_data.get("mctMinutes") or max(mct.minutes, rule.minimumMinutes))
        required_mct = max(requested_mct, mct.minutes, rule.minimumMinutes)
        mct = MinimumConnectionTime(mct.stationRef, mct.fromMode, mct.toMode, required_mct)
        validation = ConnectionValidator().ensure_valid(planned, departure, mct)
        window = ConnectionWindow.build(planned, departure, cutoff, validation.requiredMinutes, optional_dt(window_data.get("actualArrivalAt")))
        evaluation = self._evaluate_window(window, rule, at, new_prefixed_uuid7("tre"), (), topology.placeGraphVersion if topology else None, degraded_reasons)
        contract_id = require_text(data.get("contractId"), "contractId")
        contract_type = ContractType(str(data.get("contractType")))
        status = ConnectionStatus.FEASIBLE if evaluation.riskLevel is RiskLevel.FEASIBLE else ConnectionStatus.TIGHT if evaluation.riskLevel is RiskLevel.TIGHT else ConnectionStatus.AT_RISK
        guaranteed = bool(data.get("guaranteed") if data.get("guaranteed") is not None else (contract_type in PROTECTED_TYPES and plan.itineraryRef == require_text(data.get("itineraryRef"), "itineraryRef")))
        connection = Connection(new_prefixed_uuid7("con"), plan.transferPlanId, require_text(data.get("itineraryRef"), "itineraryRef"), require_text(data.get("previousSegmentRef"), "previousSegmentRef"), require_text(data.get("nextSegmentRef"), "nextSegmentRef"), tuple(str(x) for x in data.get("travelerRefs") or []), from_node_ref, to_node_ref, from_node_type, to_node_type, category, contract_id, contract_type, ConnectionStatus.PLANNED, evaluation, window, at, at, str(data.get("journeyOrderId") or plan.journeyOrderId or "").strip() or None, serviceDate=str(data.get("serviceDate") or cutoff.date().isoformat()), scheduledServiceRef=str(data.get("scheduledServiceRef") or "").strip() or None, fromMode=from_mode, toMode=to_mode, guaranteed=guaranteed, transferInstructions=cross_mode.instructions, walkingDistanceMeters=cross_mode.walkingDistanceMeters)
        connection = connection.transition(status, at)
        plan = plan.add_connection(connection.connectionId, at)
        self.store.save_plan(plan)
        self.store.save_connection(connection)
        events = [
            _envelope("ConnectionRegistered", connection.connectionId, 1, connection.to_json() | {"registeredAt": rfc3339_utc(at)}, correlation_id, causation_id, at),
            _envelope("ConnectionValidated", connection.connectionId, 1, {"connection": connection.ref_json(), "validation": validation.to_json(), "minimumConnectionTime": mct.to_json(), "guarantee": ConnectionGuarantee(connection.connectionId, guaranteed, connection.itineraryRef).to_json(), "validatedAt": rfc3339_utc(at)}, correlation_id, causation_id, at),
            self._risk_event(connection, None, correlation_id, causation_id, at, None),
        ]
        self._append(events)
        return connection.to_json()

    def reaccommodate_connection(self, connection_id: str, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        with self.transaction():
            original = self.store.get_connection(connection_id)
            case_id = require_text(data.get("caseId"), "caseId")
            if original.status in {ConnectionStatus.RECOVERED, ConnectionStatus.INVALIDATED}:
                raise PreconditionFailed("connection is not eligible for reaccommodation")
            if original.recovery is None or case_id not in original.recovery.caseIds:
                raise PreconditionFailed("caseId is not mapped to connection recovery")
            replacement_window = _replacement_window_json(dict(data.get("replacementWindow") or {}))
            window = ConnectionWindow.build(
                parse_dt(replacement_window["plannedArrivalAt"], "replacementWindow.plannedArrivalAt"),
                parse_dt(replacement_window["nextDepartureAt"], "replacementWindow.nextDepartureAt"),
                parse_dt(replacement_window["nextCutoffAt"], "replacementWindow.nextCutoffAt"),
                original.window.mctMinutes,
            )
            evaluation = RiskEvaluation(new_prefixed_uuid7("tre"), RiskLevel.FEASIBLE if window.bufferMinutes >= 10 else RiskLevel.TIGHT if window.bufferMinutes >= 0 else RiskLevel.AT_RISK, original.latestEvaluation.mctRuleId, original.latestEvaluation.mctRuleVersion, window.availableMinutes, original.latestEvaluation.requiredMinutes, ("REACCOMMODATION",), at)
            replacement_connection = Connection(
                new_prefixed_uuid7("con"), original.transferPlanId, original.itineraryRef, original.previousSegmentRef, original.nextSegmentRef, original.travelerRefs, original.fromNodeRef, original.toNodeRef, original.fromNodeType, original.toNodeType, original.transferCategory, original.contractId, original.contractType, ConnectionStatus.PLANNED, evaluation, window, at, at, original.journeyOrderId, serviceDate=original.serviceDate, scheduledServiceRef=original.scheduledServiceRef, replacementOfConnectionId=original.connectionId
            ).transition(ConnectionStatus.FEASIBLE if evaluation.riskLevel is RiskLevel.FEASIBLE else ConnectionStatus.TIGHT if evaluation.riskLevel is RiskLevel.TIGHT else ConnectionStatus.AT_RISK, at)
            plan = self.store.get_plan(original.transferPlanId).add_connection(replacement_connection.connectionId, at)
            recovered = original.recover_with_replacement(replacement_connection.connectionId, at)
            self.store.save_plan(plan)
            self.store.save_connection(replacement_connection)
            self.store.save_connection(recovered)
            registered = _envelope("ConnectionRegistered", replacement_connection.connectionId, 1, replacement_connection.to_json() | {"registeredAt": rfc3339_utc(at)}, correlation_id, causation_id, at)
            recovered_event = _envelope("ConnectionRecovered", recovered.connectionId, recovered.version + 1, {"connection": recovered.ref_json(), "previousStatus": original.status.value, "status": "RECOVERED", "riskLevel": "RECOVERED", "recoveryCaseId": case_id, "replacementConnectionId": replacement_connection.connectionId, "replacementWindow": replacement_window, "recoveredAt": rfc3339_utc(at), "reaccommodatedAt": rfc3339_utc(at), "recoverySummary": "Connection reaccommodated"}, correlation_id, causation_id, at)
            self._append([registered, recovered_event])
            return {"connection": recovered.to_json(), "replacementConnection": replacement_connection.to_json(), "caseId": case_id, "reaccommodatedAt": rfc3339_utc(at)}

    def get_connection(self, connection_id: str) -> dict[str, Any]:
        return self.store.get_connection(connection_id).to_json()

    def list_connections_by_journey(self, journey_order_id: str) -> dict[str, Any]:
        items = [c.to_json() for c in self.store.list_connections_for_journey(journey_order_id)]
        return {"items": items, "total": len(items), "limit": len(items), "offset": 0}

    def report_segment_status(self, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        pending_recovery_ids: list[str] = []
        with self.transaction():
            at = now_utc()
            report = SegmentStatusReport(new_prefixed_uuid7("tsr"), require_text(data.get("segmentRef"), "segmentRef"), ReportType(str(data.get("reportType"))), ActorRef(**dict(data.get("reportedBy") or {})), require_text(data.get("sourceSystem"), "sourceSystem"), require_text(data.get("sourceRecordId"), "sourceRecordId"), parse_dt(data.get("observedAt"), "observedAt"), optional_dt(data.get("estimatedArrivalAt")), optional_dt(data.get("actualArrivalAt")), optional_dt(data.get("cancelledAt")), str(data.get("reason") or "").strip() or None)
            source_key = segment_report_source_key(report)
            find_report = getattr(self.store, "find_report_by_source_key", None)
            existing = find_report(source_key) if callable(find_report) else None
            if existing is not None:
                report = existing
            else:
                saved = self.store.save_report(report)
                if saved is False and callable(find_report):
                    report = find_report(source_key) or report
            updated: list[Connection] = []
            events: list[EventEnvelope] = []
            suggestions = self._rebooking_suggestions(data.get("rebookingSuggestions") or data.get("alternativeConnections") or ())
            for connection in self.store.list_connections_for_segment(report.segmentRef):
                new_connection, risk_events = self._refresh_connection(connection, at, correlation_id, causation_id, report)
                events.extend(risk_events)
                if new_connection.status is ConnectionStatus.MISSED and suggestions and self._carrier_responsible(new_connection):
                    new_connection, rebooked, rebooking_events = self._auto_rebook(new_connection, suggestions, at, correlation_id, causation_id)
                    events.extend(rebooking_events)
                    updated.extend(rebooked)
                elif new_connection.status is ConnectionStatus.MISSED and self._carrier_responsible(new_connection):
                    events.append(self._rebooking_failed_event(new_connection, at, correlation_id, causation_id, "NO_ALTERNATIVE_WITHIN_4_HOURS"))
                self.store.save_connection(new_connection)
                updated.append(new_connection)
                if new_connection.status is ConnectionStatus.MISSED and new_connection.recovery and new_connection.recovery.recoveryTriggerStatus is RecoveryTriggerStatus.PENDING:
                    pending_recovery_ids.append(new_connection.connectionId)
            self._append(events)
        if pending_recovery_ids:
            self.open_pending_recoveries(pending_recovery_ids, correlation_id)
            updated = [self.store.get_connection(connection_id) for connection_id in tuple(dict.fromkeys(c.connectionId for c in updated))]
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

    def create_risk_policy(self, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        thresholds = dict(data.get("thresholds") or {})
        policy = TransferRiskPolicy(new_prefixed_uuid7("trp"), require_text(data.get("version"), "version"), RiskPolicyStatus.DRAFT, RiskThresholds(int(thresholds.get("tightMinutes") if thresholds.get("tightMinutes") is not None else 10), int(thresholds.get("atRiskMinutes") if thresholds.get("atRiskMinutes") is not None else 0)), ActorRef(**dict(data.get("createdBy") or {})), at)
        self.store.save_risk_policy(policy)
        return policy.to_json()

    def activate_risk_policy(self, policy_id: str, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        actor = ActorRef(**dict(data.get("activatedBy") or {}))
        policy = self.store.get_risk_policy(policy_id)
        active = self._active_risk_policy()
        events: list[EventEnvelope] = []
        # Validate the target BEFORE touching the currently active policy:
        # a failed activation must not leave the system policy-less.
        activated = policy.activate(at)
        if active and active.riskPolicyId != policy.riskPolicyId:
            retired = active.retire(at)
            self.store.save_risk_policy(retired)
        self.store.save_risk_policy(activated)
        if policy.status is not RiskPolicyStatus.ACTIVE:
            payload = activated.to_json() | {"activatedBy": actor.to_json(), "activatedAt": rfc3339_utc(at)}
            events.append(_envelope("RiskPolicyActivated", activated.riskPolicyId, 1, payload, correlation_id, causation_id, at))
            self._append(events)
        return activated.to_json()

    def get_active_risk_policy(self) -> dict[str, Any]:
        policy = self._active_risk_policy()
        if policy is None:
            return {"riskPolicyId": "builtin", "version": "builtin-v1", "status": "ACTIVE", "thresholds": {"tightMinutes": 10, "atRiskMinutes": 0}, "builtin": True}
        return policy.to_json()

    def handle_fulfillment_event(self, envelope: EventEnvelope, stream: str | None = None) -> bool:
        if envelope.eventType not in FULFILLMENT_SEGMENT_EVENT_TYPES:
            return False
        try:
            with self.transaction():
                mark = getattr(self.store, "mark_processed", None)
                if callable(mark) and not mark(envelope.eventId, stream):
                    return True
                report_data = segment_status_report_from_fulfillment_event(envelope)
                self.report_segment_status(report_data, envelope.correlationId, envelope.eventId)
            return True
        except DownstreamError as exc:
            LOGGER.warning("fulfillment segment event processing hit downstream error eventId=%s eventType=%s: %s", envelope.eventId, envelope.eventType, exc)
            raise

    def handle_trip_planning_event(self, envelope: EventEnvelope, stream: str | None = None) -> bool:
        if envelope.eventType != "ItineraryProposed":
            return False
        at = now_utc()
        with self.transaction():
            events = self._validate_itinerary_proposed(envelope, at)
            mark = getattr(self.store, "mark_processed", None)
            if callable(mark) and not mark(envelope.eventId, stream):
                return True
            self._append(events)
        return True

    def handle_recovery_event(self, envelope: EventEnvelope, stream: str | None = None) -> bool:
        if envelope.eventType in DISRUPTION_SEGMENT_EVENT_TYPES:
            with self.transaction():
                mark = getattr(self.store, "mark_processed", None)
                if callable(mark) and not mark(envelope.eventId, stream):
                    return True
                report_data = segment_status_report_from_disruption_event(envelope)
                self.report_segment_status(report_data, envelope.correlationId, envelope.eventId)
            return True
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
                if connection.status is ConnectionStatus.RECOVERED and connection.replacementConnectionId:
                    return True
                previous = connection.status
                connection = replace(connection, latestEvaluation=replace(connection.latestEvaluation, riskLevel=RiskLevel.RECOVERED, evaluatedAt=at), updatedAt=at)
                connection = connection.transition(ConnectionStatus.RECOVERED, at) if connection.status != ConnectionStatus.RECOVERED else connection
                event = _envelope("ConnectionRecovered", connection.connectionId, connection.version + 1, {"connection": connection.ref_json(), "previousStatus": previous.value, "status": "RECOVERED", "riskLevel": "RECOVERED", "recoveryCaseId": case_id, "disruptionRecoveryEventId": envelope.eventId, "recoveredAt": rfc3339_utc(at), "recoverySummary": "Disruption recovery completed"}, envelope.correlationId, envelope.eventId, at)
            else:
                reason = str(envelope.payload.get("reason") or "Recovery failed")[:500]
                connection = replace(connection, recovery=replace(connection.recovery, failureReason=reason), updatedAt=at)
                event = _envelope("ConnectionRecoveryFailed", connection.connectionId, connection.version + 1, {"connection": connection.ref_json(), "status": connection.status.value, "recoveryCaseId": case_id, "disruptionRecoveryEventId": envelope.eventId, "failedAt": rfc3339_utc(at), "reason": reason}, envelope.correlationId, envelope.eventId, at)
            self.store.save_connection(connection)
            self._append([event])
            return True

    def _validate_itinerary_proposed(self, envelope: EventEnvelope, at: datetime) -> list[EventEnvelope]:
        payload = dict(envelope.payload or {})
        events: list[EventEnvelope] = []
        intent_ref = str(payload.get("intentRef") or "") or None
        for itinerary in payload.get("itineraries") or ():
            if not isinstance(itinerary, Mapping):
                continue
            itinerary_ref = require_text(itinerary.get("itineraryRef"), "itinerary.itineraryRef")
            traveler_refs = tuple(str(ref) for ref in itinerary.get("travelerRefs") or payload.get("travelerRefs") or ())
            legs = tuple(leg for leg in itinerary.get("legs") or () if isinstance(leg, Mapping))
            for index, (previous_leg, next_leg) in enumerate(zip(legs, legs[1:]), start=1):
                previous_segment_ref = _leg_ref(previous_leg, index)
                next_segment_ref = _leg_ref(next_leg, index + 1)
                from_station_ref = require_text(previous_leg.get("destinationStopRef") or previous_leg.get("destinationStationRef"), "leg.destinationStopRef")
                to_station_ref = require_text(next_leg.get("originStopRef") or next_leg.get("originStationRef"), "leg.originStopRef")
                arrival_at = parse_dt(previous_leg.get("arrivalTime") or previous_leg.get("arrivalAt"), "leg.arrivalTime")
                departure_at = parse_dt(next_leg.get("departureTime") or next_leg.get("departureAt"), "leg.departureTime")
                transfer_hint = _itinerary_transfer_hint(itinerary, index)
                from_mode = _mode(previous_leg.get("mode") or transfer_hint.get("fromMode"))
                to_mode = _mode(next_leg.get("mode") or transfer_hint.get("toMode"))
                same_platform = bool(transfer_hint.get("samePlatform")) if transfer_hint.get("samePlatform") is not None else False
                override = _optional_int(transfer_hint.get("mctOverride") if transfer_hint.get("mctOverride") is not None else transfer_hint.get("mctMinutes"))
                station_ref = to_station_ref or from_station_ref
                category = TransferCategory(str(transfer_hint.get("transferCategory") or ("SAME_STATION" if from_station_ref == to_station_ref else "CROSS_STATION")))
                mct = MinimumConnectionTime.default_for(station_ref, from_mode, to_mode, same_platform, override)
                validation = ConnectionValidator().validate(arrival_at, departure_at, mct)
                transfer_id = f"{itinerary_ref}:transfer:{index}"
                proposal = TransferProposal(transfer_id, (validation,))
                rejected = False
                try:
                    proposal.validateConnectionTimes()
                except DomainError:
                    rejected = True
                connection_ref = {"connectionId": transfer_id, "itineraryRef": itinerary_ref, "previousSegmentRef": previous_segment_ref, "nextSegmentRef": next_segment_ref, "travelerRefs": list(traveler_refs)}
                event_type = "ConnectionValidationRejected" if rejected else "ConnectionValidated"
                event_payload: dict[str, Any] = {
                    "connection": connection_ref,
                    "intentRef": intent_ref,
                    "itineraryRef": itinerary_ref,
                    "transferIndex": index,
                    "fromStationRef": from_station_ref,
                    "toStationRef": to_station_ref,
                    "fromMode": from_mode.value,
                    "toMode": to_mode.value,
                    "transferCategory": category.value,
                    "validation": validation.to_json(),
                    "minimumConnectionTime": mct.to_json(),
                    "validatedAt": rfc3339_utc(at),
                }
                if rejected:
                    event_payload["rejectionReason"] = validation.reason or "CONNECTION_VALIDATION_FAILED"
                events.append(_envelope(event_type, transfer_id, 1, event_payload, envelope.correlationId, envelope.eventId, at))
        return events

    def _default_mct_rule(self, from_type: NodeType, to_type: NodeType, category: TransferCategory, mct: MinimumConnectionTime, at: datetime, source: str = "BUILTIN_STATION_POLICY") -> MctRule:
        return MctRule(f"mct-default-{mct.fromMode.value.lower()}-{mct.toMode.value.lower()}-{category.value.lower()}", 1, MctRuleStatus.PUBLISHED, from_type, to_type, category, mct.minutes, {"source": source, "stationRef": mct.stationRef}, at, publishedAt=at)

    def _carrier_responsible(self, connection: Connection) -> bool:
        return connection.guaranteed or connection.contractType in PROTECTED_TYPES

    def _rebooking_suggestions(self, raw_items: Any) -> tuple[RebookingSuggestion, ...]:
        suggestions: list[RebookingSuggestion] = []
        for item in raw_items or ():
            if not isinstance(item, Mapping):
                continue
            segment_ref = item.get("newSegmentRef") or item.get("segmentRef") or item.get("scheduledServiceRef")
            departure = item.get("newDepartureTime") or item.get("departureAt") or item.get("nextDepartureAt")
            if not segment_ref or not departure:
                continue
            suggestions.append(RebookingSuggestion(str(segment_ref), parse_dt(departure, "rebookingSuggestion.newDepartureTime"), int(item.get("additionalCost") or 0)))
        return tuple(suggestions)

    def _auto_rebook(self, connection: Connection, suggestions: Iterable[RebookingSuggestion], at: datetime, correlation_id: str, causation_id: str) -> tuple[Connection, list[Connection], list[EventEnvelope]]:
        actual_arrival = connection.window.actualArrivalAt or connection.window.plannedArrivalAt
        request = RebookingRequest(connection.connectionId, connection.nextSegmentRef, AUTO_REBOOKING_WINDOW)
        suggestion = MissedConnectionDetector().choose_rebooking(request, actual_arrival, tuple(suggestions))
        if suggestion is None:
            return connection, [], [self._rebooking_failed_event(connection, at, correlation_id, causation_id, "NO_ALTERNATIVE_WITHIN_4_HOURS")]
        no_charge = replace(suggestion, additionalCost=0)
        window = ConnectionWindow.build(actual_arrival, no_charge.newDepartureTime, no_charge.newDepartureTime, connection.window.mctMinutes)
        evaluation = RiskEvaluation(new_prefixed_uuid7("tre"), RiskLevel.FEASIBLE if window.bufferMinutes >= 0 else RiskLevel.AT_RISK, connection.latestEvaluation.mctRuleId, connection.latestEvaluation.mctRuleVersion, window.availableMinutes, connection.latestEvaluation.requiredMinutes, ("AUTO_REBOOKING",), at, connection.latestEvaluation.riskPolicyVersion, connection.latestEvaluation.placeGraphVersion, connection.latestEvaluation.degraded, connection.latestEvaluation.degradedReasons)
        replacement_connection = Connection(new_prefixed_uuid7("con"), connection.transferPlanId, connection.itineraryRef, connection.previousSegmentRef, no_charge.newSegmentRef, connection.travelerRefs, connection.fromNodeRef, connection.toNodeRef, connection.fromNodeType, connection.toNodeType, connection.transferCategory, connection.contractId, connection.contractType, ConnectionStatus.PLANNED, evaluation, window, at, at, connection.journeyOrderId, serviceDate=connection.serviceDate, scheduledServiceRef=no_charge.newSegmentRef, replacementOfConnectionId=connection.connectionId, fromMode=connection.fromMode, toMode=connection.toMode, guaranteed=connection.guaranteed, transferInstructions=connection.transferInstructions, walkingDistanceMeters=connection.walkingDistanceMeters).transition(ConnectionStatus.FEASIBLE if evaluation.riskLevel is RiskLevel.FEASIBLE else ConnectionStatus.AT_RISK, at)
        plan = self.store.get_plan(connection.transferPlanId).add_connection(replacement_connection.connectionId, at)
        recovered = connection.recover_with_replacement(replacement_connection.connectionId, at)
        self.store.save_plan(plan)
        self.store.save_connection(replacement_connection)
        event = _envelope("AutoRebookingCompleted", recovered.connectionId, recovered.version + 1, {"connection": recovered.ref_json(), "rebookingRequest": request.to_json(), "rebookingSuggestion": no_charge.to_json(), "replacementConnectionId": replacement_connection.connectionId, "additionalCost": 0, "carrierResponsible": True, "completedAt": rfc3339_utc(at)}, correlation_id, causation_id, at)
        registered = _envelope("ConnectionRegistered", replacement_connection.connectionId, 1, replacement_connection.to_json() | {"registeredAt": rfc3339_utc(at), "autoRebooking": True}, correlation_id, causation_id, at)
        return recovered, [replacement_connection], [registered, event]

    def _rebooking_failed_event(self, connection: Connection, at: datetime, correlation_id: str, causation_id: str, reason: str) -> EventEnvelope:
        request = RebookingRequest(connection.connectionId, connection.nextSegmentRef, AUTO_REBOOKING_WINDOW)
        payload = {"connection": connection.ref_json(), "rebookingRequest": request.to_json(), "reason": reason, "refundOffered": self._carrier_responsible(connection), "refundScope": "REMAINING_LEGS" if self._carrier_responsible(connection) else "VOLUNTARY_CHANGE_RULES", "failedAt": rfc3339_utc(at)}
        return _envelope("RebookingFailed", connection.connectionId, connection.version + 4, payload, correlation_id, causation_id, at)

    def _plan_json(self, plan: TransferPlan) -> dict[str, Any]:
        connections = tuple(c.summary_json() for c in self.store.list_connections_for_plan(plan.transferPlanId))
        return plan.to_json(connections)

    def _find_published_rule(self, from_type: NodeType, to_type: NodeType, category: TransferCategory, at: datetime) -> MctRule | None:
        items = list(getattr(self.store, "mct_rules", {}).values()) if hasattr(self.store, "mct_rules") else list(self.store.list_mct_rules())
        # Deterministic selection: among matching published rules, the highest
        # (version, mctRuleId) wins regardless of storage iteration order.
        matching = [rule for rule in items if rule.matches(from_type, to_type, category, at)]
        if not matching:
            return None
        return max(matching, key=lambda rule: (rule.version, rule.mctRuleId))

    def _require_published_rule(self, from_type: NodeType, to_type: NodeType, category: TransferCategory, at: datetime) -> MctRule:
        rule = self._find_published_rule(from_type, to_type, category, at)
        if rule is None:
            raise DomainError("NO_PUBLISHED_MCT_RULE")
        return rule

    def _active_risk_policy(self) -> TransferRiskPolicy | None:
        getter = getattr(self.store, "get_active_risk_policy", None)
        if callable(getter):
            return getter()
        policies = list(getattr(self.store, "risk_policies", {}).values())
        active = [policy for policy in policies if policy.status is RiskPolicyStatus.ACTIVE]
        return max(active, key=lambda p: (p.activatedAt or p.createdAt, p.riskPolicyId)) if active else None

    def _evaluate_window(self, window: ConnectionWindow, rule: MctRule, at: datetime, evaluation_id: str, extra_reasons: Iterable[str], place_graph_version: str | None = None, degraded_reasons: Iterable[str] = ()) -> RiskEvaluation:
        policy = self._active_risk_policy()
        if policy is None:
            policy = TransferRiskPolicy("builtin", "builtin-v1", RiskPolicyStatus.ACTIVE, RiskThresholds(10, 0), ActorRef("SYSTEM", "transfer-management"), at, at)
        level, reasons = policy.classify(window.availableMinutes, window.bufferMinutes, tuple(extra_reasons))
        degraded = tuple(dict.fromkeys(degraded_reasons))
        return RiskEvaluation(evaluation_id, level, rule.mctRuleId, rule.version, window.availableMinutes, window.mctMinutes, reasons, at, policy.version, place_graph_version, bool(degraded), degraded)

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
        mct = MinimumConnectionTime.default_for(connection.toNodeRef, connection.fromMode, connection.toMode, connection.transferCategory is TransferCategory.IN_STATION)
        rule = self._find_published_rule(connection.fromNodeType, connection.toNodeType, connection.transferCategory, at) or self._default_mct_rule(connection.fromNodeType, connection.toNodeType, connection.transferCategory, mct, at)
        place_graph_version = connection.latestEvaluation.placeGraphVersion
        degraded_reasons: tuple[str, ...] = ()
        try:
            topology = self.topology.fetch_snapshot(connection.fromNodeRef, connection.toNodeRef)
            if topology is not None:
                place_graph_version = topology.placeGraphVersion
        except (PlaceNetworkUnavailable, PlaceNetworkValidationError):
            degraded_reasons = ("PLACE_NETWORK_UNAVAILABLE",)
        evaluation = self._evaluate_window(window, rule, at, new_prefixed_uuid7("tre"), reasons, place_graph_version, degraded_reasons)
        updated = connection.apply_evaluation(evaluation, window, at)
        events = [self._risk_event(updated, previous_status if previous_status != updated.status else None, correlation_id, causation_id, at, report.segmentStatusReportId if report else None)]
        if updated.status is ConnectionStatus.AT_RISK and previous_status != ConnectionStatus.AT_RISK:
            events.append(_envelope("TransferAtRisk", updated.connectionId, updated.version + 2, {"connection": updated.ref_json(), "previousStatus": previous_status.value, "status": "AT_RISK", "riskLevel": "AT_RISK", "riskPolicyVersion": evaluation.riskPolicyVersion, "reasons": list(evaluation.reasons), "window": updated.window.to_json(), "detectedAt": rfc3339_utc(at)}, correlation_id, causation_id, at))
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
                if updated.recovery and updated.recovery.outboundIdempotencyKey:
                    recovery = replace(updated.recovery, recoveryTriggerStatus=RecoveryTriggerStatus.PENDING, failureReason=None)
                    updated = updated.with_recovery(recovery, at)
                else:
                    updated = updated.with_recovery(RecoveryCaseMapping(RecoveryTriggerStatus.PENDING, key), at)
            missed_event = _envelope("ConnectionMissed", updated.connectionId, updated.version + 3, {"connection": updated.ref_json(), "previousStatus": previous_status.value, "status": "MISSED", "riskLevel": "MISSED", "contractType": updated.contractType.value, "missedAt": rfc3339_utc(at), "missedCause": cause, "window": updated.window.to_json(), "recoveryRequired": recovery_required} | ({"recovery": updated.recovery.to_json()} if updated.recovery else {}), correlation_id, causation_id, at)
            events.append(missed_event)
        return updated, events

    def open_pending_recoveries(self, connection_ids: Iterable[str], correlation_id: str) -> None:
        for connection_id in tuple(dict.fromkeys(connection_ids)):
            connection = self.store.get_connection(connection_id)
            if connection.status is ConnectionStatus.MISSED and connection.recovery and connection.recovery.recoveryTriggerStatus in {RecoveryTriggerStatus.PENDING, RecoveryTriggerStatus.FAILED}:
                updated = self._open_recovery(connection, at=now_utc(), correlation_id=correlation_id)
                self.store.save_connection(updated)

    def _raise_missing_journey_order(self) -> NoReturn:
        raise DownstreamError("journeyOrderId is required to open recovery", "MISSING_JOURNEY_ORDER")

    def _open_recovery(self, connection: Connection, at: datetime, correlation_id: str) -> Connection:
        if not connection.recovery:
            return connection
        if connection.recovery.recoveryTriggerStatus is RecoveryTriggerStatus.OPENED:
            return connection
        if not connection.journeyOrderId:
            self._raise_missing_journey_order()
        evidence_id = _event_id("ConnectionMissed", connection.connectionId, connection.version)
        body = {"disruptionType": "MISSED_CONNECTION", "segmentRef": connection.nextSegmentRef, "serviceDate": connection.serviceDate or at.date().isoformat(), "evidence": {"evidenceRef": connection.connectionId, "sourceSystem": "TRANSFER_MANAGEMENT", "sourceRecordId": evidence_id, "summary": "Protected transfer connection missed", "occurredAt": rfc3339_utc(at)}, "affectedOrderIds": [connection.journeyOrderId], "reportedBy": {"actorType": "SYSTEM", "actorId": "transfer-management"}, "connectionId": connection.connectionId, "replacementWindow": {"plannedArrivalAt": rfc3339_utc(at + timedelta(minutes=15)), "nextDepartureAt": rfc3339_utc(at + timedelta(minutes=75)), "nextCutoffAt": rfc3339_utc(at + timedelta(minutes=75)), "source": "SYSTEM"}}
        if connection.scheduledServiceRef:
            body["scheduledServiceRef"] = connection.scheduledServiceRef
        response = self.downstream.report_missed_connection(body, connection.recovery.outboundIdempotencyKey, canonical_correlation_id(correlation_id))
        disruption = dict(response.get("disruption") or {})
        incident = dict(response.get("incident") or {})
        cases = tuple(str(c.get("caseId")) for c in response.get("recoveryCases") or [] if isinstance(c, Mapping) and c.get("caseId"))
        recovery = replace(connection.recovery, recoveryTriggerStatus=RecoveryTriggerStatus.OPENED, disruptionId=str(disruption.get("disruptionId") or "") or None, incidentId=str(incident.get("incidentId") or "") or None, caseIds=cases, openedAt=at, failureReason=None)
        return connection.with_recovery(recovery, at)

    def _risk_event(self, connection: Connection, previous: ConnectionStatus | None, correlation_id: str, causation_id: str, at: datetime, report_id: str | None) -> EventEnvelope:
        payload = {"connection": connection.ref_json(), "status": connection.status.value, "evaluation": connection.latestEvaluation.to_json(), "window": connection.window.to_json()}
        if previous:
            payload["previousStatus"] = previous.value
        if report_id:
            payload["sourceReportId"] = report_id
        return _envelope("TransferRiskEvaluated", connection.connectionId, connection.version + 1, payload, correlation_id, causation_id, at)
