from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import Any, Iterable, Mapping

from train_ticket_platform.events import rfc3339_utc

RISK_POLICY_VERSION = "builtin-v1"


class DomainError(ValueError):
    pass


class PreconditionFailed(DomainError):
    pass


class TransferPlanStatus(StrEnum):
    DRAFT = "DRAFT"
    EVALUATING = "EVALUATING"
    EVALUATED = "EVALUATED"
    UNSERVICEABLE = "UNSERVICEABLE"
    PUBLISHED = "PUBLISHED"
    REFRESHING = "REFRESHING"
    EXPIRED = "EXPIRED"


class ConnectionStatus(StrEnum):
    PLANNED = "PLANNED"
    FEASIBLE = "FEASIBLE"
    TIGHT = "TIGHT"
    AT_RISK = "AT_RISK"
    MISSED = "MISSED"
    RECOVERED = "RECOVERED"
    SELF_HANDLED = "SELF_HANDLED"
    COMPLETED = "COMPLETED"
    INVALIDATED = "INVALIDATED"


class ContractType(StrEnum):
    PROTECTED = "PROTECTED"
    SUPPLIER_PROTECTED = "SUPPLIER_PROTECTED"
    PLATFORM_ASSISTED = "PLATFORM_ASSISTED"
    SELF_TRANSFER = "SELF_TRANSFER"


class TransferMode(StrEnum):
    TRAIN = "TRAIN"
    METRO = "METRO"
    BUS = "BUS"
    FLIGHT = "FLIGHT"
    TAXI = "TAXI"


class ContractStatus(StrEnum):
    PROPOSED = "PROPOSED"
    ELIGIBLE = "ELIGIBLE"
    REJECTED = "REJECTED"
    CONFIRMED = "CONFIRMED"
    ACTIVE = "ACTIVE"
    TRIGGERED = "TRIGGERED"
    SETTLED = "SETTLED"
    VOIDED = "VOIDED"


class RiskLevel(StrEnum):
    FEASIBLE = "FEASIBLE"
    TIGHT = "TIGHT"
    AT_RISK = "AT_RISK"
    MISSED = "MISSED"
    RECOVERED = "RECOVERED"


class TransferCategory(StrEnum):
    SAME_STATION = "SAME_STATION"
    CROSS_STATION = "CROSS_STATION"
    IN_STATION = "IN_STATION"
    TERMINAL_CHANGE = "TERMINAL_CHANGE"
    AIRPORT = "AIRPORT"
    PORT = "PORT"
    BUS_TERMINAL = "BUS_TERMINAL"
    RIDESHARE_CONNECTOR = "RIDESHARE_CONNECTOR"
    OTHER = "OTHER"


class NodeType(StrEnum):
    STATION = "STATION"
    AIRPORT_TERMINAL = "AIRPORT_TERMINAL"
    PORT_TERMINAL = "PORT_TERMINAL"
    BUS_STOP = "BUS_STOP"
    RIDESHARE_PICKUP = "RIDESHARE_PICKUP"
    WALKING_NODE = "WALKING_NODE"
    OTHER = "OTHER"


class ReportType(StrEnum):
    DELAY = "DELAY"
    ARRIVAL = "ARRIVAL"
    CANCELLED = "CANCELLED"


class MctRuleStatus(StrEnum):
    DRAFT = "DRAFT"
    VALIDATED = "VALIDATED"
    PUBLISHED = "PUBLISHED"
    RETIRED = "RETIRED"

class RiskPolicyStatus(StrEnum):
    DRAFT = "DRAFT"
    ACTIVE = "ACTIVE"
    RETIRED = "RETIRED"


class RecoveryTriggerStatus(StrEnum):
    NOT_REQUIRED = "NOT_REQUIRED"
    PENDING = "PENDING"
    OPENED = "OPENED"
    FAILED = "FAILED"


TRANSFER_PLAN_TRANSITIONS = {
    TransferPlanStatus.DRAFT: {TransferPlanStatus.EVALUATING, TransferPlanStatus.EXPIRED},
    TransferPlanStatus.EVALUATING: {TransferPlanStatus.EVALUATED, TransferPlanStatus.UNSERVICEABLE},
    TransferPlanStatus.EVALUATED: {TransferPlanStatus.PUBLISHED, TransferPlanStatus.REFRESHING, TransferPlanStatus.EXPIRED},
    TransferPlanStatus.UNSERVICEABLE: {TransferPlanStatus.REFRESHING, TransferPlanStatus.EXPIRED},
    TransferPlanStatus.PUBLISHED: {TransferPlanStatus.REFRESHING, TransferPlanStatus.EXPIRED},
    TransferPlanStatus.REFRESHING: {TransferPlanStatus.EVALUATED, TransferPlanStatus.UNSERVICEABLE, TransferPlanStatus.EXPIRED},
    TransferPlanStatus.EXPIRED: set(),
}
CONNECTION_TRANSITIONS = {
    ConnectionStatus.PLANNED: {ConnectionStatus.FEASIBLE, ConnectionStatus.TIGHT, ConnectionStatus.AT_RISK, ConnectionStatus.INVALIDATED},
    ConnectionStatus.FEASIBLE: {ConnectionStatus.TIGHT, ConnectionStatus.AT_RISK, ConnectionStatus.COMPLETED, ConnectionStatus.INVALIDATED},
    ConnectionStatus.TIGHT: {ConnectionStatus.FEASIBLE, ConnectionStatus.AT_RISK, ConnectionStatus.MISSED, ConnectionStatus.COMPLETED},
    ConnectionStatus.AT_RISK: {ConnectionStatus.TIGHT, ConnectionStatus.MISSED, ConnectionStatus.RECOVERED},
    ConnectionStatus.MISSED: {ConnectionStatus.RECOVERED, ConnectionStatus.SELF_HANDLED},
    ConnectionStatus.RECOVERED: {ConnectionStatus.COMPLETED},
    ConnectionStatus.SELF_HANDLED: {ConnectionStatus.COMPLETED},
    ConnectionStatus.COMPLETED: set(),
    ConnectionStatus.INVALIDATED: set(),
}
CONTRACT_TRANSITIONS = {
    ContractStatus.PROPOSED: {ContractStatus.ELIGIBLE, ContractStatus.REJECTED, ContractStatus.VOIDED},
    ContractStatus.ELIGIBLE: {ContractStatus.CONFIRMED, ContractStatus.VOIDED},
    ContractStatus.REJECTED: set(),
    ContractStatus.CONFIRMED: {ContractStatus.ACTIVE, ContractStatus.VOIDED},
    ContractStatus.ACTIVE: {ContractStatus.TRIGGERED, ContractStatus.VOIDED},
    ContractStatus.TRIGGERED: {ContractStatus.SETTLED, ContractStatus.VOIDED},
    ContractStatus.SETTLED: set(),
    ContractStatus.VOIDED: set(),
}


def now_utc() -> datetime:
    return datetime.now(UTC)


def parse_dt(value: Any, field_name: str = "timestamp") -> datetime:
    if isinstance(value, datetime):
        return value.astimezone(UTC) if value.tzinfo else value.replace(tzinfo=UTC)
    if not value:
        raise DomainError(f"{field_name} is required")
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00")).astimezone(UTC)
    except ValueError as exc:
        raise DomainError(f"{field_name} must be RFC3339 UTC") from exc


def optional_dt(value: Any) -> datetime | None:
    return parse_dt(value) if value else None


def require_text(value: Any, field_name: str) -> str:
    text = str(value or "").strip()
    if not text:
        raise DomainError(f"{field_name} is required")
    return text



LARGE_HUB_STATIONS = frozenset({"北京南", "上海虹桥", "广州南"})
MEDIUM_HUB_STATIONS = frozenset({"南京南", "武汉", "成都东"})


def station_default_mct_minutes(station_ref: str) -> int:
    station = str(station_ref or "").strip()
    if station in LARGE_HUB_STATIONS:
        return 25
    if station in MEDIUM_HUB_STATIONS:
        return 20
    return 15


@dataclass(frozen=True, slots=True)
class MinimumConnectionTime:
    stationRef: str
    fromMode: TransferMode
    toMode: TransferMode
    minutes: int

    def __post_init__(self) -> None:
        require_text(self.stationRef, "stationRef")
        if self.minutes < 0:
            raise DomainError("minimum connection time must be non-negative")

    @classmethod
    def default_for(cls, station_ref: str, from_mode: TransferMode = TransferMode.TRAIN, to_mode: TransferMode = TransferMode.TRAIN, same_platform: bool = False, override_minutes: int | None = None) -> "MinimumConnectionTime":
        if override_minutes is not None:
            minutes = int(override_minutes)
        elif from_mode is TransferMode.TRAIN and to_mode is TransferMode.METRO:
            minutes = 35
        elif same_platform and from_mode is to_mode:
            minutes = 10
        else:
            minutes = station_default_mct_minutes(station_ref)
        return cls(station_ref, from_mode, to_mode, minutes)

    def to_json(self) -> dict[str, Any]:
        return {"stationRef": self.stationRef, "fromMode": self.fromMode.value, "toMode": self.toMode.value, "minutes": self.minutes}


@dataclass(frozen=True, slots=True)
class CrossModeTransfer:
    fromMode: TransferMode
    toMode: TransferMode
    fromStationRef: str
    toStationRef: str
    walkingMinutes: int
    mctOverride: int | None = None

    def __post_init__(self) -> None:
        require_text(self.fromStationRef, "fromStationRef")
        require_text(self.toStationRef, "toStationRef")
        if self.walkingMinutes < 0:
            raise DomainError("walkingMinutes must be non-negative")
        if self.mctOverride is not None and self.mctOverride <= 0:
            raise DomainError("mctOverride must be positive")

    @property
    def instructions(self) -> str:
        if self.fromMode is self.toMode:
            return f"Remain within {self.fromStationRef} and follow signs to the connecting {self.toMode.value.lower()} service."
        return f"Transfer from {self.fromMode.value} at {self.fromStationRef} to {self.toMode.value} at {self.toStationRef}; allow about {self.walkingMinutes} minutes for walking and wayfinding."

    @property
    def walkingDistanceMeters(self) -> int:
        return self.walkingMinutes * 80

    def minimum_connection_time(self) -> MinimumConnectionTime:
        return MinimumConnectionTime.default_for(self.toStationRef, self.fromMode, self.toMode, self.fromStationRef == self.toStationRef, self.mctOverride)

    def to_json(self) -> dict[str, Any]:
        data = {"fromMode": self.fromMode.value, "toMode": self.toMode.value, "fromStationRef": self.fromStationRef, "toStationRef": self.toStationRef, "walkingMinutes": self.walkingMinutes, "walkingDistanceMeters": self.walkingDistanceMeters, "instructions": self.instructions}
        if self.mctOverride is not None:
            data["mctOverride"] = self.mctOverride
        return data


@dataclass(frozen=True, slots=True)
class ConnectionGuarantee:
    transferId: str
    guaranteed: bool
    itineraryRef: str

    def __post_init__(self) -> None:
        require_text(self.transferId, "transferId")
        require_text(self.itineraryRef, "itineraryRef")

    def to_json(self) -> dict[str, Any]:
        return {"transferId": self.transferId, "guaranteed": self.guaranteed, "itineraryRef": self.itineraryRef}


@dataclass(frozen=True, slots=True)
class ConnectionValidationResult:
    valid: bool
    reason: str | None
    availableMinutes: int
    requiredMinutes: int

    def to_json(self) -> dict[str, Any]:
        data = {"valid": self.valid, "availableMinutes": self.availableMinutes, "requiredMinutes": self.requiredMinutes}
        if self.reason:
            data["reason"] = self.reason
        return data


class ConnectionValidator:
    def validate(self, arrival_at: datetime, departure_at: datetime, minimum_connection_time: MinimumConnectionTime) -> ConnectionValidationResult:
        available = int((departure_at - arrival_at).total_seconds() // 60)
        if available < minimum_connection_time.minutes:
            return ConnectionValidationResult(False, "INSUFFICIENT_CONNECTION_TIME", available, minimum_connection_time.minutes)
        return ConnectionValidationResult(True, None, available, minimum_connection_time.minutes)

    def ensure_valid(self, arrival_at: datetime, departure_at: datetime, minimum_connection_time: MinimumConnectionTime) -> ConnectionValidationResult:
        result = self.validate(arrival_at, departure_at, minimum_connection_time)
        if not result.valid:
            raise DomainError(result.reason or "CONNECTION_VALIDATION_FAILED")
        return result


@dataclass(frozen=True, slots=True)
class RebookingRequest:
    originalTransferId: str
    missedLegRef: str
    searchWindow: timedelta

    def __post_init__(self) -> None:
        require_text(self.originalTransferId, "originalTransferId")
        require_text(self.missedLegRef, "missedLegRef")
        if self.searchWindow.total_seconds() <= 0:
            raise DomainError("searchWindow must be positive")

    def to_json(self) -> dict[str, Any]:
        return {"originalTransferId": self.originalTransferId, "missedLegRef": self.missedLegRef, "searchWindowMinutes": int(self.searchWindow.total_seconds() // 60)}


@dataclass(frozen=True, slots=True)
class RebookingSuggestion:
    newSegmentRef: str
    newDepartureTime: datetime
    additionalCost: int = 0

    def __post_init__(self) -> None:
        require_text(self.newSegmentRef, "newSegmentRef")
        if self.additionalCost < 0:
            raise DomainError("additionalCost must be non-negative")

    def to_json(self) -> dict[str, Any]:
        return {"newSegmentRef": self.newSegmentRef, "newDepartureTime": rfc3339_utc(self.newDepartureTime), "additionalCost": self.additionalCost}


class MissedConnectionDetector:
    def is_missed(self, actual_arrival_at: datetime, next_departure_at: datetime, minimum_connection_time: MinimumConnectionTime) -> bool:
        return actual_arrival_at > next_departure_at - timedelta(minutes=minimum_connection_time.minutes)

    def choose_rebooking(self, request: RebookingRequest, missed_at: datetime, suggestions: Iterable[RebookingSuggestion]) -> RebookingSuggestion | None:
        latest = missed_at + request.searchWindow
        feasible = [suggestion for suggestion in suggestions if missed_at <= suggestion.newDepartureTime <= latest]
        return min(feasible, key=lambda suggestion: suggestion.newDepartureTime) if feasible else None


@dataclass(frozen=True, slots=True)
class TransferProposal:
    transferId: str
    validations: tuple[ConnectionValidationResult, ...] = ()

    def validateConnectionTimes(self) -> None:
        for validation in self.validations:
            if not validation.valid:
                raise DomainError(validation.reason or "CONNECTION_VALIDATION_FAILED")


@dataclass(frozen=True, slots=True)
class ActorRef:
    actorType: str
    actorId: str

    def __post_init__(self) -> None:
        if self.actorType not in {"SYSTEM", "OPERATIONS", "CUSTOMER_SERVICE", "USER"}:
            raise DomainError("actorType is invalid")
        require_text(self.actorId, "actorId")

    def to_json(self) -> dict[str, Any]:
        return {"actorType": self.actorType, "actorId": self.actorId}


@dataclass(frozen=True, slots=True)
class ConnectionWindow:
    plannedArrivalAt: datetime
    nextDepartureAt: datetime
    nextCutoffAt: datetime
    availableMinutes: int
    mctMinutes: int
    bufferMinutes: int
    actualArrivalAt: datetime | None = None

    @classmethod
    def build(cls, planned_arrival: datetime, next_departure: datetime, next_cutoff: datetime, mct_minutes: int, actual_arrival: datetime | None = None) -> "ConnectionWindow":
        arrival = actual_arrival or planned_arrival
        available = int((next_cutoff - arrival).total_seconds() // 60)
        return cls(planned_arrival, next_departure, next_cutoff, available, mct_minutes, available - mct_minutes, actual_arrival)

    def with_arrival(self, arrival: datetime | None) -> "ConnectionWindow":
        return ConnectionWindow.build(self.plannedArrivalAt, self.nextDepartureAt, self.nextCutoffAt, self.mctMinutes, arrival)

    def to_json(self) -> dict[str, Any]:
        data = {
            "plannedArrivalAt": rfc3339_utc(self.plannedArrivalAt),
            "nextDepartureAt": rfc3339_utc(self.nextDepartureAt),
            "nextCutoffAt": rfc3339_utc(self.nextCutoffAt),
            "availableMinutes": self.availableMinutes,
            "mctMinutes": self.mctMinutes,
            "bufferMinutes": self.bufferMinutes,
        }
        if self.actualArrivalAt:
            data["actualArrivalAt"] = rfc3339_utc(self.actualArrivalAt)
        return data


@dataclass(frozen=True, slots=True)
class RiskEvaluation:
    riskEvaluationId: str
    riskLevel: RiskLevel
    mctRuleId: str
    mctRuleVersion: int
    availableMinutes: int
    requiredMinutes: int
    reasons: tuple[str, ...]
    evaluatedAt: datetime
    riskPolicyVersion: str = RISK_POLICY_VERSION
    placeGraphVersion: str | None = None
    degraded: bool = False
    degradedReasons: tuple[str, ...] = ()

    def to_json(self) -> dict[str, Any]:
        data = {
            "riskEvaluationId": self.riskEvaluationId,
            "riskLevel": self.riskLevel.value,
            "riskPolicyVersion": self.riskPolicyVersion,
            "mctRuleId": self.mctRuleId,
            "mctRuleVersion": self.mctRuleVersion,
            "availableMinutes": self.availableMinutes,
            "requiredMinutes": self.requiredMinutes,
            "reasons": list(self.reasons),
            "evaluatedAt": rfc3339_utc(self.evaluatedAt),
        }
        if self.placeGraphVersion:
            data["placeGraphVersion"] = self.placeGraphVersion
        if self.degraded:
            data["degraded"] = True
            data["degradedReasons"] = list(self.degradedReasons)
        return data


@dataclass(frozen=True, slots=True)
class RecoveryCaseMapping:
    recoveryTriggerStatus: RecoveryTriggerStatus
    outboundIdempotencyKey: str
    caseIds: tuple[str, ...] = ()
    disruptionId: str | None = None
    incidentId: str | None = None
    openedAt: datetime | None = None
    failureReason: str | None = None

    def to_json(self) -> dict[str, Any]:
        data = {"recoveryTriggerStatus": self.recoveryTriggerStatus.value, "caseIds": list(self.caseIds), "outboundIdempotencyKey": self.outboundIdempotencyKey}
        if self.disruptionId:
            data["disruptionId"] = self.disruptionId
        if self.incidentId:
            data["incidentId"] = self.incidentId
        if self.openedAt:
            data["openedAt"] = rfc3339_utc(self.openedAt)
        if self.failureReason:
            data["failureReason"] = self.failureReason
        return data


@dataclass(frozen=True, slots=True)
class TransferPlan:
    transferPlanId: str
    itineraryRef: str
    planningSnapshotVersion: int
    journeyOrderId: str | None
    travelerRefs: tuple[str, ...]
    status: TransferPlanStatus
    connections: tuple[str, ...]
    evaluationVersion: int
    createdAt: datetime
    updatedAt: datetime
    expiresAt: datetime | None = None
    riskPolicyVersion: str = RISK_POLICY_VERSION
    version: int = 0

    def transition(self, target: TransferPlanStatus, at: datetime) -> "TransferPlan":
        if target == self.status:
            return self
        if target not in TRANSFER_PLAN_TRANSITIONS[self.status]:
            raise PreconditionFailed(f"cannot transition transfer plan from {self.status.value} to {target.value}")
        return replace(self, status=target, updatedAt=at, version=self.version + 1)

    def evaluated(self, snapshot_version: int, connection_ids: tuple[str, ...], at: datetime, unserviceable: bool = False) -> "TransferPlan":
        working = self
        if working.status not in {TransferPlanStatus.EVALUATING, TransferPlanStatus.REFRESHING}:
            target = TransferPlanStatus.REFRESHING if working.status in {TransferPlanStatus.EVALUATED, TransferPlanStatus.PUBLISHED, TransferPlanStatus.UNSERVICEABLE} else TransferPlanStatus.EVALUATING
            working = working.transition(target, at)
        final = TransferPlanStatus.UNSERVICEABLE if unserviceable else TransferPlanStatus.EVALUATED
        return replace(working.transition(final, at), planningSnapshotVersion=snapshot_version, connections=connection_ids, evaluationVersion=self.evaluationVersion + 1, updatedAt=at)

    def add_connection(self, connection_id: str, at: datetime) -> "TransferPlan":
        return replace(self, connections=tuple(dict.fromkeys((*self.connections, connection_id))), updatedAt=at, version=self.version + 1)

    def to_json(self, connections: tuple[Mapping[str, Any], ...] = ()) -> dict[str, Any]:
        data = {
            "transferPlanId": self.transferPlanId,
            "itineraryRef": self.itineraryRef,
            "planningSnapshotVersion": self.planningSnapshotVersion,
            "status": self.status.value,
            "connections": list(connections),
            "evaluationVersion": self.evaluationVersion,
            "riskPolicyVersion": self.riskPolicyVersion,
            "createdAt": rfc3339_utc(self.createdAt),
            "updatedAt": rfc3339_utc(self.updatedAt),
        }
        if self.journeyOrderId:
            data["journeyOrderId"] = self.journeyOrderId
        if self.expiresAt:
            data["expiresAt"] = rfc3339_utc(self.expiresAt)
        return data


@dataclass(frozen=True, slots=True)
class Connection:
    connectionId: str
    transferPlanId: str
    itineraryRef: str
    previousSegmentRef: str
    nextSegmentRef: str
    travelerRefs: tuple[str, ...]
    fromNodeRef: str
    toNodeRef: str
    fromNodeType: NodeType
    toNodeType: NodeType
    transferCategory: TransferCategory
    contractId: str
    contractType: ContractType
    status: ConnectionStatus
    latestEvaluation: RiskEvaluation
    window: ConnectionWindow
    createdAt: datetime
    updatedAt: datetime
    journeyOrderId: str | None = None
    recovery: RecoveryCaseMapping | None = None
    serviceDate: str | None = None
    scheduledServiceRef: str | None = None
    replacementOfConnectionId: str | None = None
    replacementConnectionId: str | None = None
    fromMode: TransferMode = TransferMode.TRAIN
    toMode: TransferMode = TransferMode.TRAIN
    guaranteed: bool = False
    transferInstructions: str | None = None
    walkingDistanceMeters: int | None = None
    version: int = 0

    def transition(self, target: ConnectionStatus, at: datetime) -> "Connection":
        if target == self.status:
            return self
        if target not in CONNECTION_TRANSITIONS[self.status]:
            raise PreconditionFailed(f"cannot transition connection from {self.status.value} to {target.value}")
        return replace(self, status=target, updatedAt=at, version=self.version + 1)

    def apply_evaluation(self, evaluation: RiskEvaluation, window: ConnectionWindow, at: datetime) -> "Connection":
        target = {
            RiskLevel.FEASIBLE: ConnectionStatus.FEASIBLE,
            RiskLevel.TIGHT: ConnectionStatus.TIGHT,
            RiskLevel.AT_RISK: ConnectionStatus.AT_RISK,
            RiskLevel.MISSED: ConnectionStatus.MISSED,
            RiskLevel.RECOVERED: ConnectionStatus.RECOVERED,
        }[evaluation.riskLevel]
        if self.status == ConnectionStatus.MISSED and target not in {ConnectionStatus.RECOVERED, ConnectionStatus.MISSED}:
            target = ConnectionStatus.MISSED
        updated = replace(self, latestEvaluation=evaluation, window=window, updatedAt=at)
        if updated.status is ConnectionStatus.FEASIBLE and target is ConnectionStatus.MISSED:
            updated = updated.transition(ConnectionStatus.AT_RISK, at)
        return updated.transition(target, at) if target != updated.status else replace(updated, version=updated.version + 1)

    def with_recovery(self, recovery: RecoveryCaseMapping, at: datetime) -> "Connection":
        return replace(self, recovery=recovery, updatedAt=at, version=self.version + 1)

    def recover_with_replacement(self, replacement_connection_id: str, at: datetime) -> "Connection":
        if self.status in {ConnectionStatus.RECOVERED, ConnectionStatus.INVALIDATED}:
            raise PreconditionFailed("connection is not eligible for reaccommodation")
        recovered = replace(self, replacementConnectionId=require_text(replacement_connection_id, "replacementConnectionId"), latestEvaluation=replace(self.latestEvaluation, riskLevel=RiskLevel.RECOVERED, evaluatedAt=at), updatedAt=at)
        return recovered.transition(ConnectionStatus.RECOVERED, at) if recovered.status is not ConnectionStatus.RECOVERED else recovered

    def summary_json(self) -> dict[str, Any]:
        return {"connectionId": self.connectionId, "previousSegmentRef": self.previousSegmentRef, "nextSegmentRef": self.nextSegmentRef, "transferCategory": self.transferCategory.value, "status": self.status.value, "riskLevel": self.latestEvaluation.riskLevel.value}

    def ref_json(self) -> dict[str, Any]:
        data = {"connectionId": self.connectionId, "transferPlanId": self.transferPlanId, "itineraryRef": self.itineraryRef, "previousSegmentRef": self.previousSegmentRef, "nextSegmentRef": self.nextSegmentRef, "travelerRefs": list(self.travelerRefs)}
        if self.journeyOrderId:
            data["journeyOrderId"] = self.journeyOrderId
        return data

    def to_json(self) -> dict[str, Any]:
        data = {
            "connectionId": self.connectionId,
            "transferPlanId": self.transferPlanId,
            "itineraryRef": self.itineraryRef,
            "previousSegmentRef": self.previousSegmentRef,
            "nextSegmentRef": self.nextSegmentRef,
            "travelerRefs": list(self.travelerRefs),
            "fromNodeRef": self.fromNodeRef,
            "toNodeRef": self.toNodeRef,
            "fromNodeType": self.fromNodeType.value,
            "toNodeType": self.toNodeType.value,
            "transferCategory": self.transferCategory.value,
            "contractId": self.contractId,
            "contractType": self.contractType.value,
            "status": self.status.value,
            "latestEvaluation": self.latestEvaluation.to_json(),
            "window": self.window.to_json(),
            "createdAt": rfc3339_utc(self.createdAt),
            "updatedAt": rfc3339_utc(self.updatedAt),
        }
        if self.journeyOrderId:
            data["journeyOrderId"] = self.journeyOrderId
        if self.replacementOfConnectionId:
            data["replacementOfConnectionId"] = self.replacementOfConnectionId
        if self.replacementConnectionId:
            data["replacementConnectionId"] = self.replacementConnectionId
        data["fromMode"] = self.fromMode.value
        data["toMode"] = self.toMode.value
        data["guaranteed"] = self.guaranteed
        if self.transferInstructions:
            data["transferInstructions"] = self.transferInstructions
        if self.walkingDistanceMeters is not None:
            data["walkingDistanceMeters"] = self.walkingDistanceMeters
        if self.recovery and self.recovery.recoveryTriggerStatus is not RecoveryTriggerStatus.NOT_REQUIRED:
            data["recovery"] = self.recovery.to_json()
        return data


@dataclass(frozen=True, slots=True)
class ConnectionContract:
    connectionContractId: str
    connectionId: str
    contractType: ContractType
    status: ContractStatus
    responsibleParty: str
    coverageSummary: str
    disclosureVersion: str
    createdAt: datetime
    updatedAt: datetime
    termsSnapshotRef: str | None = None
    confirmedAt: datetime | None = None
    version: int = 0

    def transition(self, target: ContractStatus, at: datetime) -> "ConnectionContract":
        if target == self.status:
            return self
        if target not in CONTRACT_TRANSITIONS[self.status]:
            raise PreconditionFailed(f"cannot transition connection contract from {self.status.value} to {target.value}")
        return replace(self, status=target, updatedAt=at, confirmedAt=at if target is ContractStatus.CONFIRMED else self.confirmedAt, version=self.version + 1)

    def to_json(self) -> dict[str, Any]:
        data = {"connectionContractId": self.connectionContractId, "connectionId": self.connectionId, "contractType": self.contractType.value, "status": self.status.value, "responsibleParty": self.responsibleParty, "coverageSummary": self.coverageSummary, "disclosureVersion": self.disclosureVersion, "createdAt": rfc3339_utc(self.createdAt), "updatedAt": rfc3339_utc(self.updatedAt)}
        if self.termsSnapshotRef:
            data["termsSnapshotRef"] = self.termsSnapshotRef
        if self.confirmedAt:
            data["confirmedAt"] = rfc3339_utc(self.confirmedAt)
        return data


@dataclass(frozen=True, slots=True)
class MctRule:
    mctRuleId: str
    version: int
    status: MctRuleStatus
    fromNodeType: NodeType
    toNodeType: NodeType
    transferCategory: TransferCategory
    minimumMinutes: int
    conditions: Mapping[str, Any]
    validFrom: datetime
    validUntil: datetime | None = None
    publishedAt: datetime | None = None
    retiredAt: datetime | None = None

    def __post_init__(self) -> None:
        if self.minimumMinutes < 0:
            raise DomainError("minimumMinutes must be non-negative")

    def update(self, data: Mapping[str, Any]) -> "MctRule":
        if self.status is MctRuleStatus.PUBLISHED:
            raise PreconditionFailed("published MCT rules are immutable")
        if self.status is MctRuleStatus.RETIRED:
            raise PreconditionFailed("retired MCT rules are immutable")
        minimum = int(data.get("minimumMinutes", self.minimumMinutes))
        if minimum < 0:
            raise DomainError("minimumMinutes must be non-negative")
        return replace(self, minimumMinutes=minimum, conditions=dict(data.get("conditions", self.conditions)), validFrom=optional_dt(data.get("validFrom")) or self.validFrom, validUntil=optional_dt(data.get("validUntil")) if "validUntil" in data else self.validUntil, version=self.version + 1)

    def publish(self, at: datetime) -> "MctRule":
        if self.status is MctRuleStatus.PUBLISHED:
            return self
        if self.status is MctRuleStatus.RETIRED:
            raise PreconditionFailed("retired MCT rule cannot be published")
        return replace(self, status=MctRuleStatus.PUBLISHED, publishedAt=at)

    def retire(self, at: datetime) -> "MctRule":
        if self.status is not MctRuleStatus.PUBLISHED:
            raise PreconditionFailed("only published MCT rules can be retired")
        return replace(self, status=MctRuleStatus.RETIRED, retiredAt=at)

    def matches(self, from_type: NodeType, to_type: NodeType, category: TransferCategory, at: datetime) -> bool:
        return self.status is MctRuleStatus.PUBLISHED and self.fromNodeType == from_type and self.toNodeType == to_type and self.transferCategory == category and self.validFrom <= at and (self.validUntil is None or at < self.validUntil)

    def to_json(self) -> dict[str, Any]:
        data = {"mctRuleId": self.mctRuleId, "version": self.version, "status": self.status.value, "fromNodeType": self.fromNodeType.value, "toNodeType": self.toNodeType.value, "transferCategory": self.transferCategory.value, "minimumMinutes": self.minimumMinutes, "conditions": dict(self.conditions), "validFrom": rfc3339_utc(self.validFrom)}
        if self.validUntil:
            data["validUntil"] = rfc3339_utc(self.validUntil)
        if self.publishedAt:
            data["publishedAt"] = rfc3339_utc(self.publishedAt)
        if self.retiredAt:
            data["retiredAt"] = rfc3339_utc(self.retiredAt)
        return data


@dataclass(frozen=True, slots=True)
class SegmentStatusReport:
    segmentStatusReportId: str
    segmentRef: str
    reportType: ReportType
    reportedBy: ActorRef
    sourceSystem: str
    sourceRecordId: str
    observedAt: datetime
    estimatedArrivalAt: datetime | None = None
    actualArrivalAt: datetime | None = None
    cancelledAt: datetime | None = None
    reason: str | None = None

    def __post_init__(self) -> None:
        if self.sourceSystem not in {"OPERATIONS", "ADMIN", "FULFILLMENT", "FULFILLMENT-EVENT"}:
            raise DomainError("sourceSystem is invalid")
        if self.reportType is ReportType.DELAY and self.estimatedArrivalAt is None:
            raise DomainError("estimatedArrivalAt is required for DELAY")
        if self.reportType is ReportType.ARRIVAL and self.actualArrivalAt is None:
            raise DomainError("actualArrivalAt is required for ARRIVAL")
        if self.reportType is ReportType.CANCELLED and self.cancelledAt is None:
            raise DomainError("cancelledAt is required for CANCELLED")

    def to_json(self) -> dict[str, Any]:
        data = {"segmentStatusReportId": self.segmentStatusReportId, "segmentRef": self.segmentRef, "reportType": self.reportType.value, "reportedBy": self.reportedBy.to_json(), "sourceSystem": self.sourceSystem, "sourceRecordId": self.sourceRecordId, "observedAt": rfc3339_utc(self.observedAt)}
        if self.estimatedArrivalAt:
            data["estimatedArrivalAt"] = rfc3339_utc(self.estimatedArrivalAt)
        if self.actualArrivalAt:
            data["actualArrivalAt"] = rfc3339_utc(self.actualArrivalAt)
        if self.cancelledAt:
            data["cancelledAt"] = rfc3339_utc(self.cancelledAt)
        if self.reason:
            data["reason"] = self.reason
        return data


@dataclass(frozen=True, slots=True)
class RiskThresholds:
    tightMinutes: int
    atRiskMinutes: int

    def __post_init__(self) -> None:
        if self.tightMinutes < 0 or self.atRiskMinutes < 0:
            raise DomainError("risk policy thresholds must be non-negative")

    def to_json(self) -> dict[str, int]:
        return {"tightMinutes": self.tightMinutes, "atRiskMinutes": self.atRiskMinutes}


@dataclass(frozen=True, slots=True)
class TransferRiskPolicy:
    riskPolicyId: str
    version: str
    status: RiskPolicyStatus
    thresholds: RiskThresholds
    createdBy: ActorRef
    createdAt: datetime
    activatedAt: datetime | None = None
    retiredAt: datetime | None = None

    def activate(self, at: datetime) -> "TransferRiskPolicy":
        if self.status is RiskPolicyStatus.RETIRED:
            raise PreconditionFailed("retired risk policy cannot be activated")
        if self.status is RiskPolicyStatus.ACTIVE:
            return self
        return replace(self, status=RiskPolicyStatus.ACTIVE, activatedAt=at)

    def retire(self, at: datetime) -> "TransferRiskPolicy":
        if self.status is RiskPolicyStatus.RETIRED:
            return self
        return replace(self, status=RiskPolicyStatus.RETIRED, retiredAt=at)

    def classify(self, available_minutes: int, buffer_minutes: int, reasons: Iterable[str]) -> tuple[RiskLevel, tuple[str, ...]]:
        explanation = list(reasons)
        if "NEXT_SEGMENT_CANCELLED" in explanation or "PREVIOUS_SEGMENT_CANCELLED" in explanation:
            return RiskLevel.MISSED, tuple(dict.fromkeys(explanation))
        if available_minutes < 0:
            explanation.append("CUTOFF_EXPIRED")
            return RiskLevel.MISSED, tuple(dict.fromkeys(explanation))
        if buffer_minutes < self.thresholds.atRiskMinutes:
            explanation.append(f"THRESHOLD_AT_RISK_BUFFER_LT_{self.thresholds.atRiskMinutes}_MIN")
            return RiskLevel.AT_RISK, tuple(dict.fromkeys(explanation))
        if buffer_minutes < self.thresholds.tightMinutes:
            explanation.append(f"THRESHOLD_TIGHT_BUFFER_LT_{self.thresholds.tightMinutes}_MIN")
            return RiskLevel.TIGHT, tuple(dict.fromkeys(explanation))
        return RiskLevel.FEASIBLE, tuple(dict.fromkeys(explanation))

    def to_json(self) -> dict[str, Any]:
        data = {"riskPolicyId": self.riskPolicyId, "version": self.version, "status": self.status.value, "thresholds": self.thresholds.to_json(), "createdBy": self.createdBy.to_json(), "createdAt": rfc3339_utc(self.createdAt)}
        if self.activatedAt:
            data["activatedAt"] = rfc3339_utc(self.activatedAt)
        if self.retiredAt:
            data["retiredAt"] = rfc3339_utc(self.retiredAt)
        return data
