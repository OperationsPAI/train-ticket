from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import Any, Mapping

from train_ticket_platform.events import rfc3339_utc


class DomainError(ValueError):
    pass


class DomainRuleViolation(DomainError):
    pass


class PreconditionFailed(DomainError):
    pass


class RecoveryCaseStatus(StrEnum):
    OPENED = "OPENED"
    ASSESSING_IMPACT = "ASSESSING_IMPACT"
    OPTIONS_GENERATED = "OPTIONS_GENERATED"
    AWAITING_USER_CHOICE = "AWAITING_USER_CHOICE"
    EXECUTING_RECOVERY = "EXECUTING_RECOVERY"
    MANUAL_REVIEW = "MANUAL_REVIEW"
    RECOVERED = "RECOVERED"
    DECLINED = "DECLINED"
    FAILED = "FAILED"
    CLOSED = "CLOSED"


class RecoveryOptionType(StrEnum):
    WAIT = "WAIT"
    REFUND = "REFUND"
    COMPENSATION = "COMPENSATION"
    REACCOMMODATION = "REACCOMMODATION"
    MANUAL = "MANUAL"


class ExecutionTarget(StrEnum):
    NONE = "NONE"
    POST_SALES = "POST_SALES"
    WALLET_PROMOTION = "WALLET_PROMOTION"
    TRANSFER_MANAGEMENT = "TRANSFER_MANAGEMENT"
    MANUAL_QUEUE = "MANUAL_QUEUE"


class DisruptionType(StrEnum):
    DELAY = "DELAY"
    CANCELLATION = "CANCELLATION"
    PARTIAL = "PARTIAL"
    FORCE_MAJEURE = "FORCE_MAJEURE"


class SeverityLevel(StrEnum):
    MINOR = "MINOR"
    MODERATE = "MODERATE"
    SEVERE = "SEVERE"
    CRITICAL = "CRITICAL"


class CompensationStatus(StrEnum):
    NOT_ELIGIBLE = "NOT_ELIGIBLE"
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    PAID = "PAID"
    REJECTED = "REJECTED"


class SeatClass(StrEnum):
    BUSINESS = "BUSINESS"
    FIRST = "FIRST"
    SECOND = "SECOND"


class ReroutingDecision(StrEnum):
    AUTO_REBOOK = "AUTO_REBOOK"
    OFFER_REFUND = "OFFER_REFUND"
    MANUAL_REVIEW = "MANUAL_REVIEW"


class CompensationDeliveryMethod(StrEnum):
    POINTS = "POINTS"
    CASH = "CASH"


class CompensationClaimStatus(StrEnum):
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    PAID = "PAID"
    REJECTED = "REJECTED"


class ProcessingBatchStatus(StrEnum):
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    COMPLETED = "COMPLETED"


LEGACY_DISRUPTION_TYPE_MAP = {
    "SERVICE_DELAY": DisruptionType.DELAY,
    "SERVICE_CANCELLED": DisruptionType.CANCELLATION,
    "SERVICE_SUSPENDED": DisruptionType.CANCELLATION,
    "SAILING_SUSPENDED": DisruptionType.CANCELLATION,
    "STOP_CHANGED": DisruptionType.PARTIAL,
    "PORT_CALL_CHANGED": DisruptionType.PARTIAL,
    "ROAD_CLOSED": DisruptionType.FORCE_MAJEURE,
    "WEATHER": DisruptionType.FORCE_MAJEURE,
    "OPERATION_RESTRICTION": DisruptionType.FORCE_MAJEURE,
    "SUPPLIER_FAILURE": DisruptionType.FORCE_MAJEURE,
    "DRIVER_CANCELLED": DisruptionType.CANCELLATION,
    "DISPATCH_FAILED": DisruptionType.CANCELLATION,
    "CONNECTION_MISSED": DisruptionType.PARTIAL,
    "MISSED_CONNECTION": DisruptionType.PARTIAL,
    "BATCH_SYSTEM_EVENT": DisruptionType.PARTIAL,
}


DISRUPTION_TYPES = {
    "SERVICE_DELAY",
    "SERVICE_CANCELLED",
    "SERVICE_SUSPENDED",
    "SAILING_SUSPENDED",
    "ROAD_CLOSED",
    "WEATHER",
    "OPERATION_RESTRICTION",
    "SUPPLIER_FAILURE",
    "DRIVER_CANCELLED",
    "DISPATCH_FAILED",
    "STOP_CHANGED",
    "PORT_CALL_CHANGED",
    "BATCH_SYSTEM_EVENT",
    "CONNECTION_MISSED",
    "MISSED_CONNECTION",
    "DELAY",
    "CANCELLATION",
    "PARTIAL",
    "FORCE_MAJEURE",
}


TERMINAL_BEFORE_CLOSE = {RecoveryCaseStatus.RECOVERED, RecoveryCaseStatus.DECLINED, RecoveryCaseStatus.FAILED}
ALLOWED_TRANSITIONS: dict[RecoveryCaseStatus, set[RecoveryCaseStatus]] = {
    RecoveryCaseStatus.OPENED: {RecoveryCaseStatus.ASSESSING_IMPACT, RecoveryCaseStatus.MANUAL_REVIEW},
    RecoveryCaseStatus.ASSESSING_IMPACT: {RecoveryCaseStatus.OPTIONS_GENERATED, RecoveryCaseStatus.MANUAL_REVIEW},
    RecoveryCaseStatus.OPTIONS_GENERATED: {RecoveryCaseStatus.EXECUTING_RECOVERY, RecoveryCaseStatus.AWAITING_USER_CHOICE, RecoveryCaseStatus.MANUAL_REVIEW},
    RecoveryCaseStatus.AWAITING_USER_CHOICE: {RecoveryCaseStatus.EXECUTING_RECOVERY, RecoveryCaseStatus.MANUAL_REVIEW, RecoveryCaseStatus.DECLINED},
    RecoveryCaseStatus.EXECUTING_RECOVERY: {RecoveryCaseStatus.RECOVERED, RecoveryCaseStatus.OPTIONS_GENERATED, RecoveryCaseStatus.MANUAL_REVIEW, RecoveryCaseStatus.FAILED},
    RecoveryCaseStatus.MANUAL_REVIEW: {RecoveryCaseStatus.EXECUTING_RECOVERY, RecoveryCaseStatus.RECOVERED, RecoveryCaseStatus.FAILED},
    RecoveryCaseStatus.RECOVERED: {RecoveryCaseStatus.CLOSED},
    RecoveryCaseStatus.DECLINED: {RecoveryCaseStatus.CLOSED},
    RecoveryCaseStatus.FAILED: {RecoveryCaseStatus.CLOSED},
    RecoveryCaseStatus.CLOSED: set(),
}


def now_utc() -> datetime:
    return datetime.now(UTC)


def require_text(value: str | None, field_name: str) -> str:
    if value is None or not str(value).strip():
        raise DomainError(f"{field_name} is required")
    return str(value).strip()


@dataclass(frozen=True, slots=True)
class ActorRef:
    actorType: str
    actorId: str

    def __post_init__(self) -> None:
        if self.actorType not in {"USER", "CUSTOMER_SERVICE", "OPERATIONS", "SYSTEM"}:
            raise DomainError("actorType is invalid")
        require_text(self.actorId, "actorId")

    def to_json(self) -> dict[str, Any]:
        return {"actorType": self.actorType, "actorId": self.actorId}


@dataclass(frozen=True, slots=True)
class Evidence:
    evidenceRef: str
    sourceSystem: str
    sourceRecordId: str
    summary: str
    occurredAt: str | None = None

    def __post_init__(self) -> None:
        require_text(self.evidenceRef, "evidenceRef")
        if self.sourceSystem not in {"CUSTOMER_SERVICE", "ADMIN", "TRANSFER_MANAGEMENT", "PROVIDER_INTEGRATION", "FULFILLMENT"}:
            raise DomainError("evidence.sourceSystem is invalid")
        require_text(self.sourceRecordId, "sourceRecordId")
        require_text(self.summary, "summary")

    def to_json(self) -> dict[str, Any]:
        data = {"evidenceRef": self.evidenceRef, "sourceSystem": self.sourceSystem, "sourceRecordId": self.sourceRecordId, "summary": self.summary}
        if self.occurredAt:
            data["occurredAt"] = self.occurredAt
        return data


@dataclass(frozen=True, slots=True)
class Incident:
    incidentId: str
    status: str
    disruptionType: str
    scheduledServiceRef: str | None
    segmentRefs: tuple[str, ...]
    serviceDate: str
    evidenceRefs: tuple[str, ...]
    affectedOrderIds: tuple[str, ...]
    openedAt: datetime
    updatedAt: datetime
    version: int = 0

    @classmethod
    def open(cls, incident_id: str, disruption_type: str, scheduled_service_ref: str | None, segment_ref: str | None, service_date: str, evidence_ref: str, affected_order_ids: tuple[str, ...], opened_at: datetime) -> "Incident":
        return cls(incident_id, "CONFIRMED", disruption_type, scheduled_service_ref, tuple([segment_ref] if segment_ref else ()), service_date, (evidence_ref,), tuple(dict.fromkeys(affected_order_ids)), opened_at, opened_at)

    def merge(self, evidence_ref: str, segment_ref: str | None, affected_order_ids: tuple[str, ...], at: datetime) -> "Incident":
        return replace(
            self,
            segmentRefs=tuple(dict.fromkeys((*self.segmentRefs, *([segment_ref] if segment_ref else [])))),
            evidenceRefs=tuple(dict.fromkeys((*self.evidenceRefs, evidence_ref))),
            affectedOrderIds=tuple(dict.fromkeys((*self.affectedOrderIds, *affected_order_ids))),
            updatedAt=at,
        )

    def to_json(self) -> dict[str, Any]:
        data = {
            "incidentId": self.incidentId,
            "status": self.status,
            "disruptionType": self.disruptionType,
            "serviceDate": self.serviceDate,
            "evidenceRefs": list(self.evidenceRefs),
            "affectedOrderCount": len(set(self.affectedOrderIds)),
            "openedAt": rfc3339_utc(self.openedAt),
            "updatedAt": rfc3339_utc(self.updatedAt),
        }
        if self.scheduledServiceRef:
            data["scheduledServiceRef"] = self.scheduledServiceRef
        if self.segmentRefs:
            data["segmentRefs"] = list(self.segmentRefs)
        return data


@dataclass(frozen=True, slots=True)
class RecoveryOption:
    optionId: str
    optionType: RecoveryOptionType
    title: str
    description: str
    executionTarget: ExecutionTarget
    refund: Mapping[str, Any] | None = None
    compensation: Mapping[str, Any] | None = None
    manualReason: str | None = None
    reaccommodation: Mapping[str, Any] | None = None
    expiresAt: datetime | None = None

    def to_json(self) -> dict[str, Any]:
        data: dict[str, Any] = {"optionId": self.optionId, "optionType": self.optionType.value, "title": self.title, "description": self.description, "executionTarget": self.executionTarget.value}
        if self.refund is not None:
            data["refund"] = dict(self.refund)
        if self.compensation is not None:
            data["compensation"] = dict(self.compensation)
        if self.manualReason:
            data["manualReason"] = self.manualReason
        if self.reaccommodation is not None:
            data["reaccommodation"] = dict(self.reaccommodation)
        if self.expiresAt:
            data["expiresAt"] = rfc3339_utc(self.expiresAt)
        return data


@dataclass(frozen=True, slots=True)
class RecoveryOptionSet:
    optionSetId: str
    caseId: str
    options: tuple[RecoveryOption, ...]
    generatedAt: datetime
    expiresAt: datetime | None
    requiresUserChoice: bool

    def option_by_id(self, option_id: str) -> RecoveryOption:
        for option in self.options:
            if option.optionId == option_id:
                return option
        raise DomainError("selected option does not belong to the current option set")

    def to_json(self) -> dict[str, Any]:
        data = {"optionSetId": self.optionSetId, "caseId": self.caseId, "options": [item.to_json() for item in self.options], "generatedAt": rfc3339_utc(self.generatedAt), "requiresUserChoice": self.requiresUserChoice}
        if self.expiresAt:
            data["expiresAt"] = rfc3339_utc(self.expiresAt)
        return data


@dataclass(frozen=True, slots=True)
class RecoveryExecution:
    executionId: str
    target: ExecutionTarget
    idempotencyKey: str | None
    externalRef: str | None
    startedAt: datetime
    completedAt: datetime | None = None
    failureReason: str | None = None

    def to_json(self) -> dict[str, Any]:
        data = {"executionId": self.executionId, "target": self.target.value, "startedAt": rfc3339_utc(self.startedAt)}
        if self.idempotencyKey:
            data["idempotencyKey"] = self.idempotencyKey
        if self.externalRef:
            data["externalRef"] = self.externalRef
        if self.completedAt:
            data["completedAt"] = rfc3339_utc(self.completedAt)
        if self.failureReason:
            data["failureReason"] = self.failureReason
        return data


@dataclass(frozen=True, slots=True)
class RecoveryCase:
    caseId: str
    incidentId: str
    journeyOrderId: str
    affectedScope: Mapping[str, Any]
    status: RecoveryCaseStatus
    openedAt: datetime
    updatedAt: datetime
    optionSet: RecoveryOptionSet | None = None
    selectedOptionId: str | None = None
    execution: RecoveryExecution | None = None
    version: int = 0

    def transition(self, target: RecoveryCaseStatus, at: datetime) -> "RecoveryCase":
        if target not in ALLOWED_TRANSITIONS[self.status]:
            raise DomainRuleViolation(f"cannot transition recovery case from {self.status.value} to {target.value}")
        return replace(self, status=target, updatedAt=at)

    def assess(self, at: datetime) -> "RecoveryCase":
        return self.transition(RecoveryCaseStatus.ASSESSING_IMPACT, at)

    def attach_options(self, option_set: RecoveryOptionSet, at: datetime) -> "RecoveryCase":
        generated = self.transition(RecoveryCaseStatus.OPTIONS_GENERATED, at)
        target = RecoveryCaseStatus.AWAITING_USER_CHOICE if option_set.requiresUserChoice else RecoveryCaseStatus.EXECUTING_RECOVERY
        return replace(generated.transition(target, at), optionSet=option_set)

    def select(self, option_id: str, actor: ActorRef, execution_id: str, idempotency_key: str | None, at: datetime) -> tuple["RecoveryCase", RecoveryOption]:
        if self.status is not RecoveryCaseStatus.AWAITING_USER_CHOICE:
            raise PreconditionFailed("recovery case is not awaiting user choice")
        if self.optionSet is None:
            raise PreconditionFailed("recovery option set is missing")
        if self.optionSet.expiresAt and at >= self.optionSet.expiresAt:
            raise PreconditionFailed("recovery option set is expired")
        if self.selectedOptionId:
            raise PreconditionFailed("recovery option is already selected")
        if actor.actorType not in {"USER", "CUSTOMER_SERVICE"}:
            raise DomainError("selectedBy.actorType must be USER or CUSTOMER_SERVICE")
        option = self.optionSet.option_by_id(option_id)
        if option.optionType is RecoveryOptionType.MANUAL:
            selected = self.transition(RecoveryCaseStatus.MANUAL_REVIEW, at)
            execution = RecoveryExecution(execution_id, ExecutionTarget.MANUAL_QUEUE, None, f"manual:{self.caseId}", at)
        else:
            selected = self.transition(RecoveryCaseStatus.EXECUTING_RECOVERY, at)
            execution = RecoveryExecution(execution_id, option.executionTarget, idempotency_key, None, at)
        return replace(selected, selectedOptionId=option_id, execution=execution), option

    def auto_wait(self, actor: ActorRef, execution_id: str, at: datetime) -> tuple["RecoveryCase", RecoveryOption]:
        if self.optionSet is None:
            raise PreconditionFailed("recovery option set is missing")
        wait = next((item for item in self.optionSet.options if item.optionType is RecoveryOptionType.WAIT), None)
        if wait is None:
            raise DomainError("WAIT option is missing")
        executing = replace(self, selectedOptionId=wait.optionId, execution=RecoveryExecution(execution_id, ExecutionTarget.NONE, None, None, at))
        return executing.complete(wait, None, at), wait

    def complete(self, option: RecoveryOption, external_ref: str | None, at: datetime) -> "RecoveryCase":
        if self.status is not RecoveryCaseStatus.EXECUTING_RECOVERY:
            raise DomainError("only executing recovery cases can complete")
        execution = self.execution
        if execution is None:
            raise DomainError("recovery execution is missing")
        completed = replace(execution, externalRef=external_ref or execution.externalRef, completedAt=at)
        return replace(self.transition(RecoveryCaseStatus.RECOVERED, at), execution=completed)

    def fail(self, reason: str, at: datetime) -> "RecoveryCase":
        if self.status not in {RecoveryCaseStatus.EXECUTING_RECOVERY, RecoveryCaseStatus.MANUAL_REVIEW}:
            raise DomainError("only executing or manual-review cases can fail")
        execution = self.execution
        if execution:
            execution = replace(execution, failureReason=reason, completedAt=at)
        return replace(self.transition(RecoveryCaseStatus.FAILED, at), execution=execution)

    def resolve_manual(self, status: RecoveryCaseStatus, at: datetime, external_ref: str | None = None) -> "RecoveryCase":
        if self.status is not RecoveryCaseStatus.MANUAL_REVIEW:
            raise PreconditionFailed("case is not in manual review")
        if status not in {RecoveryCaseStatus.RECOVERED, RecoveryCaseStatus.FAILED}:
            raise DomainError("manual review can only resolve to RECOVERED or FAILED")
        resolved = self.transition(status, at)
        if resolved.execution and external_ref:
            resolved = replace(resolved, execution=replace(resolved.execution, externalRef=external_ref, completedAt=at))
        return resolved

    def close(self, at: datetime) -> "RecoveryCase":
        if self.status not in TERMINAL_BEFORE_CLOSE:
            raise PreconditionFailed("only RECOVERED, DECLINED, or FAILED cases can be closed")
        return self.transition(RecoveryCaseStatus.CLOSED, at)

    def selected_option(self) -> RecoveryOption | None:
        if not self.optionSet or not self.selectedOptionId:
            return None
        return self.optionSet.option_by_id(self.selectedOptionId)

    def to_json(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "caseId": self.caseId,
            "incidentId": self.incidentId,
            "journeyOrderId": self.journeyOrderId,
            "affectedScope": dict(self.affectedScope),
            "status": self.status.value,
            "openedAt": rfc3339_utc(self.openedAt),
            "updatedAt": rfc3339_utc(self.updatedAt),
        }
        if self.optionSet:
            data["optionSet"] = self.optionSet.to_json()
        if self.selectedOptionId:
            data["selectedOptionId"] = self.selectedOptionId
        if self.execution:
            data["execution"] = self.execution.to_json()
        return data


def build_option_set(
    case_id: str,
    journey_order_id: str,
    segment_ref: str | None,
    generated_at: datetime,
    option_set_id: str,
    option_ids: tuple[str, str, str, str],
    wait_only: bool,
    reaccommodation: Mapping[str, Any] | None = None,
) -> RecoveryOptionSet:
    expires = None if wait_only else generated_at + timedelta(hours=24)
    wait = RecoveryOption(option_ids[0], RecoveryOptionType.WAIT, "Wait for service recovery", "Keep the current trip and wait for operations recovery.", ExecutionTarget.NONE, expiresAt=expires)
    if reaccommodation is not None:
        recovery_option = RecoveryOption(
            option_ids[1],
            RecoveryOptionType.REACCOMMODATION,
            "Reaccommodate protected transfer",
            "Register a replacement protected transfer connection.",
            ExecutionTarget.TRANSFER_MANAGEMENT,
            reaccommodation=dict(reaccommodation),
            expiresAt=expires,
        )
        return RecoveryOptionSet(option_set_id, case_id, (wait, recovery_option), generated_at, expires, True)
    if wait_only:
        return RecoveryOptionSet(option_set_id, case_id, (wait,), generated_at, None, False)
    refund_scope = {"orderItemRefs": [], "segmentRefs": [segment_ref] if segment_ref else [], "travelerRefs": [], "entitlementRefs": []}
    refund = RecoveryOption(option_ids[1], RecoveryOptionType.REFUND, "Refund disrupted trip", "Open a disruption refund case for the affected order.", ExecutionTarget.POST_SALES, refund={"reasonCode": "DISRUPTION_REFUND", "scope": refund_scope}, expiresAt=expires)
    comp = RecoveryOption(option_ids[2], RecoveryOptionType.COMPENSATION, "Issue compensation credit", "Issue a wallet compensation credit for this disruption.", ExecutionTarget.WALLET_PROMOTION, compensation={"amount": {"currency": "CNY", "minorUnits": 1000}, "benefitType": "COMPENSATION_CREDIT", "balanceType": "PROMOTION_CREDIT", "validUntil": rfc3339_utc(generated_at + timedelta(days=30)), "reasonCode": "DISRUPTION_COMP"}, expiresAt=expires)
    manual = RecoveryOption(option_ids[3], RecoveryOptionType.MANUAL, "Manual review", "Send the recovery case to customer-service manual review.", ExecutionTarget.MANUAL_QUEUE, manualReason="Customer-service review requested", expiresAt=expires)
    return RecoveryOptionSet(option_set_id, case_id, (wait, refund, comp, manual), generated_at, expires, True)


def _coerce_utc(value: datetime) -> datetime:
    return (value if value.tzinfo else value.replace(tzinfo=UTC)).astimezone(UTC)


@dataclass(frozen=True, slots=True)
class Disruption:
    disruptionId: str
    segmentRef: str
    type: DisruptionType
    severity: SeverityLevel
    declaredAt: datetime
    estimatedResolution: datetime | None
    affectedPassengerCount: int
    delayMinutes: int | None = None

    def __post_init__(self) -> None:
        require_text(self.disruptionId, "disruptionId")
        require_text(self.segmentRef, "segmentRef")
        if self.affectedPassengerCount < 0:
            raise DomainError("affectedPassengerCount cannot be negative")
        if self.delayMinutes is not None and self.delayMinutes < 0:
            raise DomainError("delayMinutes cannot be negative")

    def to_json(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "disruptionId": self.disruptionId,
            "segmentRef": self.segmentRef,
            "type": self.type.value,
            "severity": self.severity.value,
            "declaredAt": rfc3339_utc(self.declaredAt),
            "affectedPassengerCount": self.affectedPassengerCount,
        }
        if self.estimatedResolution:
            data["estimatedResolution"] = rfc3339_utc(self.estimatedResolution)
        if self.delayMinutes is not None:
            data["delayMinutes"] = self.delayMinutes
        return data


class DisruptionClassifier:
    @staticmethod
    def classify(disruption_type: DisruptionType | str, delay_minutes: int | None = None) -> SeverityLevel:
        dtype = normalize_disruption_type(disruption_type)
        if dtype in {DisruptionType.CANCELLATION, DisruptionType.FORCE_MAJEURE}:
            return SeverityLevel.CRITICAL
        if dtype is DisruptionType.PARTIAL:
            return SeverityLevel.MODERATE
        delay = max(0, int(delay_minutes or 0))
        if delay < 30:
            return SeverityLevel.MINOR
        if delay <= 120:
            return SeverityLevel.MODERATE
        return SeverityLevel.SEVERE


@dataclass(frozen=True, slots=True)
class AffectedBooking:
    orderId: str
    travelerId: str
    segmentRef: str
    compensationStatus: CompensationStatus = CompensationStatus.PENDING
    origin: str | None = None
    destination: str | None = None
    originalDeparture: datetime | None = None
    seatClass: SeatClass = SeatClass.SECOND
    ticketPriceMinorUnits: int = 0
    currency: str = "CNY"

    def __post_init__(self) -> None:
        require_text(self.orderId, "orderId")
        require_text(self.travelerId, "travelerId")
        require_text(self.segmentRef, "segmentRef")
        if self.ticketPriceMinorUnits < 0:
            raise DomainError("ticketPriceMinorUnits cannot be negative")

    def to_json(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "orderId": self.orderId,
            "travelerId": self.travelerId,
            "segmentRef": self.segmentRef,
            "compensationStatus": self.compensationStatus.value,
            "seatClass": self.seatClass.value,
            "ticketPrice": {"currency": self.currency, "minorUnits": self.ticketPriceMinorUnits},
        }
        if self.origin:
            data["origin"] = self.origin
        if self.destination:
            data["destination"] = self.destination
        if self.originalDeparture:
            data["originalDeparture"] = rfc3339_utc(self.originalDeparture)
        return data


@dataclass(frozen=True, slots=True)
class AlternativeRoute:
    segmentRef: str
    departureTime: datetime
    seatClass: SeatClass
    transfers: int = 0
    origin: str | None = None
    destination: str | None = None

    def __post_init__(self) -> None:
        require_text(self.segmentRef, "segmentRef")
        if self.transfers < 0:
            raise DomainError("transfers cannot be negative")


@dataclass(frozen=True, slots=True)
class ReroutingSuggestion:
    newSegmentRef: str
    score: float
    departureTime: datetime
    seatClass: SeatClass
    transfers: int

    def to_json(self) -> dict[str, Any]:
        return {
            "newSegmentRef": self.newSegmentRef,
            "score": round(self.score, 2),
            "departureTime": rfc3339_utc(self.departureTime),
            "seatClass": self.seatClass.value,
            "transfers": self.transfers,
        }


@dataclass(frozen=True, slots=True)
class ReroutingOutcome:
    orderId: str
    decision: ReroutingDecision
    suggestion: ReroutingSuggestion | None = None

    def to_json(self) -> dict[str, Any]:
        data: dict[str, Any] = {"orderId": self.orderId, "decision": self.decision.value}
        if self.suggestion:
            data["suggestion"] = self.suggestion.to_json()
        return data


class ReroutingEngine:
    AUTO_REBOOK_THRESHOLD = 80.0
    SEARCH_WINDOW = timedelta(hours=4)

    def suggest(self, booking: AffectedBooking, alternatives: tuple[AlternativeRoute, ...]) -> ReroutingSuggestion | None:
        if booking.originalDeparture is None:
            return None
        candidates = [route for route in alternatives if self._matches(booking, route)]
        suggestions = tuple(self.score(booking, route) for route in candidates)
        if not suggestions:
            return None
        return max(suggestions, key=lambda suggestion: suggestion.score)

    def decide(self, booking: AffectedBooking, alternatives: tuple[AlternativeRoute, ...]) -> ReroutingOutcome:
        suggestion = self.suggest(booking, alternatives)
        if suggestion is None:
            return ReroutingOutcome(booking.orderId, ReroutingDecision.OFFER_REFUND)
        if suggestion.score >= self.AUTO_REBOOK_THRESHOLD:
            return ReroutingOutcome(booking.orderId, ReroutingDecision.AUTO_REBOOK, suggestion)
        return ReroutingOutcome(booking.orderId, ReroutingDecision.MANUAL_REVIEW, suggestion)

    def score(self, booking: AffectedBooking, route: AlternativeRoute) -> ReroutingSuggestion:
        if booking.originalDeparture is None:
            raise DomainError("booking.originalDeparture is required to score rerouting")
        deviation = abs((_coerce_utc(route.departureTime) - _coerce_utc(booking.originalDeparture)).total_seconds()) / 60
        time_score = max(0.0, 100.0 - deviation / 2.0)
        class_score = self._class_score(booking.seatClass, route.seatClass)
        transfer_score = 20 if route.transfers == 0 else 10 if route.transfers == 1 else 0
        return ReroutingSuggestion(route.segmentRef, time_score + class_score + transfer_score, route.departureTime, route.seatClass, route.transfers)

    def _matches(self, booking: AffectedBooking, route: AlternativeRoute) -> bool:
        if booking.originalDeparture is None:
            return False
        if abs(_coerce_utc(route.departureTime) - _coerce_utc(booking.originalDeparture)) > self.SEARCH_WINDOW:
            return False
        if booking.origin and route.origin and booking.origin != route.origin:
            return False
        if booking.destination and route.destination and booking.destination != route.destination:
            return False
        return True

    @staticmethod
    def _class_score(original: SeatClass, replacement: SeatClass) -> int:
        rank = {SeatClass.SECOND: 1, SeatClass.FIRST: 2, SeatClass.BUSINESS: 3}
        if replacement is original:
            return 30
        if rank[replacement] < rank[original]:
            return 15
        return 25


@dataclass(frozen=True, slots=True)
class CompensationPolicy:
    delaySeverity: SeverityLevel
    compensationPct: int
    deliveryMethod: CompensationDeliveryMethod = CompensationDeliveryMethod.POINTS


@dataclass(frozen=True, slots=True)
class CompensationAward:
    refundMinorUnits: int
    compensationMinorUnits: int
    currency: str
    deliveryMethod: CompensationDeliveryMethod

    @property
    def totalMinorUnits(self) -> int:
        return self.refundMinorUnits + self.compensationMinorUnits

    def to_json(self) -> dict[str, Any]:
        return {
            "refund": {"currency": self.currency, "minorUnits": self.refundMinorUnits},
            "compensation": {"currency": self.currency, "minorUnits": self.compensationMinorUnits},
            "total": {"currency": self.currency, "minorUnits": self.totalMinorUnits},
            "method": self.deliveryMethod.value,
        }


class CompensationCalculator:
    def calculate(self, ticket_price_minor_units: int, delay_minutes: int | None, disruption_type: DisruptionType | str, currency: str = "CNY", delivery_method: CompensationDeliveryMethod = CompensationDeliveryMethod.POINTS) -> CompensationAward:
        if ticket_price_minor_units < 0:
            raise DomainError("ticketPriceMinorUnits cannot be negative")
        dtype = normalize_disruption_type(disruption_type)
        refund = 0
        pct = 0
        if dtype is DisruptionType.FORCE_MAJEURE:
            refund = ticket_price_minor_units
        elif dtype is DisruptionType.CANCELLATION:
            refund = ticket_price_minor_units
            pct = 25
        elif dtype is DisruptionType.DELAY:
            delay = max(0, int(delay_minutes or 0))
            if delay >= 120:
                pct = 50
            elif delay >= 60:
                pct = 25
        return CompensationAward(refund, ticket_price_minor_units * pct // 100, currency, delivery_method)


@dataclass(frozen=True, slots=True)
class CompensationClaim:
    claimId: str
    orderId: str
    amount: CompensationAward
    status: CompensationClaimStatus = CompensationClaimStatus.PENDING
    issuedBy: datetime | None = None

    def to_json(self) -> dict[str, Any]:
        data = {"claimId": self.claimId, "orderId": self.orderId, "amount": self.amount.to_json(), "status": self.status.value}
        if self.issuedBy:
            data["issuedBy"] = rfc3339_utc(self.issuedBy)
        return data


@dataclass(frozen=True, slots=True)
class ProcessingBatch:
    batchId: str
    bookings: tuple[AffectedBooking, ...]
    status: ProcessingBatchStatus = ProcessingBatchStatus.PENDING
    processedCount: int = 0

    def complete(self, processed_count: int | None = None) -> "ProcessingBatch":
        count = len(self.bookings) if processed_count is None else processed_count
        if count < 0 or count > len(self.bookings):
            raise DomainError("processedCount is invalid")
        return replace(self, status=ProcessingBatchStatus.COMPLETED, processedCount=count)

    def to_json(self) -> dict[str, Any]:
        return {"batchId": self.batchId, "status": self.status.value, "processedCount": self.processedCount, "bookingCount": len(self.bookings), "orderIds": [booking.orderId for booking in self.bookings]}


@dataclass(frozen=True, slots=True)
class MassDisruptionProgress:
    totalAffected: int
    processed: int = 0
    rebooked: int = 0
    refunded: int = 0
    pending: int = 0

    @classmethod
    def empty(cls, total_affected: int) -> "MassDisruptionProgress":
        return cls(total_affected, 0, 0, 0, total_affected)

    def record(self, outcomes: tuple[ReroutingOutcome, ...]) -> "MassDisruptionProgress":
        rebooked = sum(1 for outcome in outcomes if outcome.decision is ReroutingDecision.AUTO_REBOOK)
        refunded = sum(1 for outcome in outcomes if outcome.decision is ReroutingDecision.OFFER_REFUND)
        processed = min(self.totalAffected, self.processed + len(outcomes))
        return MassDisruptionProgress(self.totalAffected, processed, self.rebooked + rebooked, self.refunded + refunded, max(0, self.totalAffected - processed))

    def to_json(self) -> dict[str, int]:
        return {"totalAffected": self.totalAffected, "processed": self.processed, "rebooked": self.rebooked, "refunded": self.refunded, "pending": self.pending}


class MassDisruptionProcessor:
    BATCH_SIZE = 100
    _priority = {SeatClass.BUSINESS: 0, SeatClass.FIRST: 1, SeatClass.SECOND: 2}

    def create_batches(self, bookings: tuple[AffectedBooking, ...], batch_id_factory: Any) -> tuple[ProcessingBatch, ...]:
        ordered = tuple(sorted(bookings, key=lambda booking: (self._priority[booking.seatClass], booking.orderId)))
        return tuple(ProcessingBatch(str(batch_id_factory()), ordered[index:index + self.BATCH_SIZE]) for index in range(0, len(ordered), self.BATCH_SIZE))

    def process(self, bookings: tuple[AffectedBooking, ...], alternatives: tuple[AlternativeRoute, ...], batch_id_factory: Any) -> tuple[tuple[ProcessingBatch, ...], tuple[ReroutingOutcome, ...], MassDisruptionProgress]:
        engine = ReroutingEngine()
        progress = MassDisruptionProgress.empty(len(bookings))
        completed_batches: list[ProcessingBatch] = []
        all_outcomes: list[ReroutingOutcome] = []
        for batch in self.create_batches(bookings, batch_id_factory):
            outcomes = tuple(engine.decide(booking, alternatives) for booking in batch.bookings)
            all_outcomes.extend(outcomes)
            progress = progress.record(outcomes)
            completed_batches.append(batch.complete(len(outcomes)))
        return tuple(completed_batches), tuple(all_outcomes), progress


def normalize_disruption_type(disruption_type: DisruptionType | str) -> DisruptionType:
    if isinstance(disruption_type, DisruptionType):
        return disruption_type
    value = require_text(str(disruption_type), "disruptionType").upper()
    if value in DisruptionType.__members__:
        return DisruptionType[value]
    mapped = LEGACY_DISRUPTION_TYPE_MAP.get(value)
    if mapped is None:
        raise DomainError("disruptionType is invalid")
    return mapped
