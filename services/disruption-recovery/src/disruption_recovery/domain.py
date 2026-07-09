from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import Any, Mapping

from train_ticket_platform.events import rfc3339_utc


class DomainError(ValueError):
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
    MANUAL = "MANUAL"


class ExecutionTarget(StrEnum):
    NONE = "NONE"
    POST_SALES = "POST_SALES"
    WALLET_PROMOTION = "WALLET_PROMOTION"
    MANUAL_QUEUE = "MANUAL_QUEUE"


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
        if self.sourceSystem not in {"CUSTOMER_SERVICE", "ADMIN"}:
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
    expiresAt: datetime | None = None

    def to_json(self) -> dict[str, Any]:
        data: dict[str, Any] = {"optionId": self.optionId, "optionType": self.optionType.value, "title": self.title, "description": self.description, "executionTarget": self.executionTarget.value}
        if self.refund is not None:
            data["refund"] = dict(self.refund)
        if self.compensation is not None:
            data["compensation"] = dict(self.compensation)
        if self.manualReason:
            data["manualReason"] = self.manualReason
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
            raise DomainError(f"cannot transition recovery case from {self.status.value} to {target.value}")
        return replace(self, status=target, updatedAt=at)

    def assess(self, at: datetime) -> "RecoveryCase":
        return self.transition(RecoveryCaseStatus.ASSESSING_IMPACT, at)

    def attach_options(self, option_set: RecoveryOptionSet, at: datetime) -> "RecoveryCase":
        generated = self.transition(RecoveryCaseStatus.OPTIONS_GENERATED, at)
        target = RecoveryCaseStatus.AWAITING_USER_CHOICE if option_set.requiresUserChoice else RecoveryCaseStatus.EXECUTING_RECOVERY
        return replace(generated.transition(target, at), optionSet=option_set)

    def select(self, option_id: str, actor: ActorRef, execution_id: str, idempotency_key: str | None, at: datetime) -> tuple["RecoveryCase", RecoveryOption]:
        if self.status is not RecoveryCaseStatus.AWAITING_USER_CHOICE:
            raise DomainError("recovery case is not awaiting user choice")
        if self.optionSet is None:
            raise DomainError("recovery option set is missing")
        if self.optionSet.expiresAt and at >= self.optionSet.expiresAt:
            raise DomainError("recovery option set is expired")
        if self.selectedOptionId:
            raise DomainError("recovery option is already selected")
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
            raise DomainError("recovery option set is missing")
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
            raise DomainError("case is not in manual review")
        if status not in {RecoveryCaseStatus.RECOVERED, RecoveryCaseStatus.FAILED}:
            raise DomainError("manual review can only resolve to RECOVERED or FAILED")
        resolved = self.transition(status, at)
        if resolved.execution and external_ref:
            resolved = replace(resolved, execution=replace(resolved.execution, externalRef=external_ref, completedAt=at))
        return resolved

    def close(self, at: datetime) -> "RecoveryCase":
        if self.status not in TERMINAL_BEFORE_CLOSE:
            raise DomainError("only RECOVERED, DECLINED, or FAILED cases can be closed")
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


def build_option_set(case_id: str, journey_order_id: str, segment_ref: str | None, generated_at: datetime, option_set_id: str, option_ids: tuple[str, str, str, str], wait_only: bool) -> RecoveryOptionSet:
    expires = None if wait_only else generated_at + timedelta(hours=24)
    wait = RecoveryOption(option_ids[0], RecoveryOptionType.WAIT, "Wait for service recovery", "Keep the current trip and wait for operations recovery.", ExecutionTarget.NONE, expiresAt=expires)
    if wait_only:
        return RecoveryOptionSet(option_set_id, case_id, (wait,), generated_at, None, False)
    refund_scope = {"orderItemRefs": [], "segmentRefs": [segment_ref] if segment_ref else [], "travelerRefs": [], "entitlementRefs": []}
    refund = RecoveryOption(option_ids[1], RecoveryOptionType.REFUND, "Refund disrupted trip", "Open a disruption refund case for the affected order.", ExecutionTarget.POST_SALES, refund={"reasonCode": "DISRUPTION_REFUND", "scope": refund_scope}, expiresAt=expires)
    comp = RecoveryOption(option_ids[2], RecoveryOptionType.COMPENSATION, "Issue compensation credit", "Issue a wallet compensation credit for this disruption.", ExecutionTarget.WALLET_PROMOTION, compensation={"amount": {"currency": "CNY", "minorUnits": 1000}, "benefitType": "COMPENSATION_CREDIT", "balanceType": "PROMOTION_CREDIT", "validUntil": rfc3339_utc(generated_at + timedelta(days=30)), "reasonCode": "DISRUPTION_COMP"}, expiresAt=expires)
    manual = RecoveryOption(option_ids[3], RecoveryOptionType.MANUAL, "Manual review", "Send the recovery case to customer-service manual review.", ExecutionTarget.MANUAL_QUEUE, manualReason="Customer-service review requested", expiresAt=expires)
    return RecoveryOptionSet(option_set_id, case_id, (wait, refund, comp, manual), generated_at, expires, True)
