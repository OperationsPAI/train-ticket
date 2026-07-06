from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any, TypeAlias
from uuid import NAMESPACE_URL, uuid5

from train_ticket_platform.events import EventEnvelope, envelope_factory, rfc3339_utc
from train_ticket_platform.idempotency import IdempotencyRecord, IdempotencyStore
from train_ticket_platform.ids import is_prefixed_uuid7, is_uuid7, new_prefixed_uuid7, new_uuid7
from train_ticket_platform.messaging import (
    EventPublisher,
    EventSubscriber,
    FatalHandlerError,
    HandlerError,
    InMemoryEventPublisher,
    InMemoryEventSubscriber as PlatformInMemoryEventSubscriber,
    PublishFailed,
    SubscribeFailed,
    TransientHandlerError,
)

from .domain import BlockScope, Decision, PolicyVersionRef, RiskAssessment, RiskLevel, allow_subject, assess_risk, block_subject

PRODUCER = "risk-compliance"
SCHEMA_VERSION = 1
DEFAULT_POLICY_VERSION = PolicyVersionRef(policy_set_id="risk-rules", version="1.0.0")
FREQUENCY_WINDOW = timedelta(minutes=10)
FREQUENCY_THRESHOLD = 3
BLOCKING_DECISIONS = {Decision.DENY, Decision.CHALLENGE}
BLOCKING_DECISION_VALUES = {decision.value for decision in BLOCKING_DECISIONS}
RiskScenario: TypeAlias = str
EventHandler: TypeAlias = Callable[[EventEnvelope], None]
InMemoryEventSubscriber = PlatformInMemoryEventSubscriber


@dataclass(frozen=True, slots=True)
class RiskAssessmentResult:
    assessmentId: str
    subjectRef: str
    scenario: str
    decision: str
    score: int
    level: str
    policyVersion: str
    reasonCode: str
    reasonExplanation: str
    assessedAt: str
    evidenceRef: str
    assessmentSnapshotHash: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "assessmentId": self.assessmentId,
            "subjectRef": self.subjectRef,
            "scenario": self.scenario,
            "decision": self.decision,
            "score": self.score,
            "level": self.level,
            "policyVersion": self.policyVersion,
            "reasonCode": self.reasonCode,
            "reasonExplanation": self.reasonExplanation,
            "assessedAt": self.assessedAt,
        }

    def to_event_payload(self) -> dict[str, Any]:
        return {
            **self.to_dict(),
            "evidenceRef": self.evidenceRef,
            "assessmentSnapshotHash": self.assessmentSnapshotHash,
        }


@dataclass(frozen=True, slots=True)
class RiskBlockApplied:
    blockId: str
    subjectRef: str
    scope: str
    reasonCode: str
    policyVersion: str
    evidenceRef: str
    blockedAt: str

    def to_event_payload(self) -> dict[str, Any]:
        return {
            "blockId": self.blockId,
            "subjectRef": self.subjectRef,
            "scope": self.scope,
            "reasonCode": self.reasonCode,
            "policyVersion": self.policyVersion,
            "evidenceRef": self.evidenceRef,
            "blockedAt": self.blockedAt,
        }


@dataclass(frozen=True, slots=True)
class RiskBlockLifted:
    allowId: str
    subjectRef: str
    scope: str
    reasonCode: str
    policyVersion: str
    evidenceRef: str
    allowedAt: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "allowId": self.allowId,
            "subjectRef": self.subjectRef,
            "scope": self.scope,
            "reasonCode": self.reasonCode,
            "policyVersion": self.policyVersion,
            "evidenceRef": self.evidenceRef,
            "allowedAt": self.allowedAt,
        }

    def to_event_payload(self) -> dict[str, Any]:
        return self.to_dict()


class AssessmentNotFoundError(KeyError):
    pass


class BlockNotFoundError(KeyError):
    pass


class InMemoryAssessmentRepository:
    def __init__(self) -> None:
        self._assessments: dict[str, RiskAssessmentResult] = {}
        self._blocks: dict[str, RiskBlockApplied] = {}
        self._processed_event_ids: set[str] = set()
        self._account_order_times: dict[str, list[datetime]] = {}
        self._account_lifted_at: dict[str, datetime] = {}
        self._order_accounts: dict[str, str] = {}

    def get(self, assessment_id: str) -> RiskAssessmentResult:
        try:
            return self._assessments[assessment_id]
        except KeyError as exc:
            raise AssessmentNotFoundError(assessment_id) from exc

    def find(self, assessment_id: str) -> RiskAssessmentResult | None:
        return self._assessments.get(assessment_id)

    def count(self) -> int:
        return len(self._assessments)

    def values(self) -> tuple[RiskAssessmentResult, ...]:
        return tuple(self._assessments.values())

    def save(self, assessment: RiskAssessmentResult) -> None:
        self._assessments[assessment.assessmentId] = assessment

    def is_processed(self, event_id: str) -> bool:
        return event_id in self._processed_event_ids

    def record_processed(self, event_id: str) -> None:
        self._processed_event_ids.add(event_id)

    def save_block(self, block: RiskBlockApplied) -> None:
        self._blocks[block.subjectRef] = block

    def active_block(self, subject_ref: str) -> RiskBlockApplied:
        try:
            return self._blocks[subject_ref]
        except KeyError as exc:
            raise BlockNotFoundError(subject_ref) from exc

    def remove_block(self, subject_ref: str) -> None:
        self._blocks.pop(subject_ref, None)

    def remember_order_account(self, order_id: str, account_id: str) -> None:
        self._order_accounts[order_id] = account_id

    def account_for_order(self, order_id: str) -> str | None:
        return self._order_accounts.get(order_id)

    def record_account_lift(self, account_id: str, lifted_at: datetime) -> None:
        self._account_lifted_at[account_id] = lifted_at
        self._account_order_times[account_id] = [
            seen_at for seen_at in self._account_order_times.get(account_id, [])
            if seen_at > lifted_at
        ]

    def record_order_attempt(self, account_id: str, occurred_at: datetime) -> int:
        lifted_at = self._account_lifted_at.get(account_id)
        attempts = [
            seen_at for seen_at in self._account_order_times.get(account_id, [])
            if occurred_at - seen_at <= FREQUENCY_WINDOW and (lifted_at is None or seen_at > lifted_at)
        ]
        attempts.append(occurred_at)
        self._account_order_times[account_id] = attempts
        return len(attempts)


@dataclass(slots=True)
class RiskComplianceService:
    publisher: EventPublisher
    repository: InMemoryAssessmentRepository = field(default_factory=InMemoryAssessmentRepository)
    idempotency_store: IdempotencyStore | None = None

    def assess(
        self,
        *,
        subject_ref: str,
        scenario: RiskScenario,
        context: Mapping[str, Any],
        idempotency_key: str,
        correlation_id: str,
        idempotency_scope: str | None = None,
        idempotency_fingerprint: str | None = None,
    ) -> tuple[RiskAssessmentResult, bool]:
        if self.idempotency_store is not None and idempotency_scope is not None and idempotency_fingerprint is not None:
            existing = self.idempotency_store.get(idempotency_scope, idempotency_key)
            if existing is not None and existing.pending_events:
                for pending_event in existing.pending_events:
                    self.publisher.publish(EventEnvelope.from_json_dict(pending_event))
                self.idempotency_store.put(
                    idempotency_scope,
                    idempotency_key,
                    IdempotencyRecord(
                        fingerprint=existing.fingerprint,
                        status_code=existing.status_code,
                        response_body=existing.response_body,
                        headers=existing.headers,
                    ),
                )
                return _result_from_response_body(existing.response_body), True

        assessment_id = prefixed_id("asmt")
        requested = assess_risk(
            assessment_id=assessment_id,
            subject_ref=subject_ref,
            scenario=scenario,
            input_data=context,
            idempotency_key=idempotency_key,
        )
        completed = _evaluate(requested)
        result = _to_result(completed)
        envelope = _assessment_envelope(result, correlation_id, prefixed_id("cmd"))
        self.repository.save(result)
        try:
            self.publisher.publish(envelope)
        except PublishFailed:
            if self.idempotency_store is not None and idempotency_scope is not None and idempotency_fingerprint is not None:
                self.idempotency_store.put(
                    idempotency_scope,
                    idempotency_key,
                    IdempotencyRecord(
                        fingerprint=idempotency_fingerprint,
                        status_code=201,
                        response_body=result.to_dict(),
                        headers={"content-type": "application/json"},
                        pending_events=(envelope.to_json_dict(),),
                    ),
                )
            raise
        return result, False

    def get_assessment(self, assessment_id: str) -> RiskAssessmentResult:
        return self.repository.get(assessment_id)

    def assessment_count(self) -> int:
        return self.repository.count()

    def assessments(self) -> tuple[RiskAssessmentResult, ...]:
        return self.repository.values()

    def handle_event(self, envelope: EventEnvelope) -> None:
        if envelope.eventType != "JourneyOrderCreated":
            return
        if self.repository.is_processed(envelope.eventId):
            return
        result, block = self._assess_journey_order_created(envelope)
        self.repository.save(result)
        assessment_envelope = _assessment_envelope(
            result,
            envelope.correlationId,
            envelope.eventId,
            event_id=deterministic_event_id(result.assessmentId),
        )
        self.publisher.publish(assessment_envelope)
        if block is not None:
            self.repository.save_block(block)
            self.publisher.publish(_block_applied_envelope(block, envelope.correlationId, assessment_envelope.eventId))
        self.repository.record_processed(envelope.eventId)

    def lift_block(self, *, subject_ref: str, scope: str, reason_code: str, correlation_id: str) -> RiskBlockLifted:
        previous = self.repository.active_block(subject_ref)
        if previous.scope != scope:
            raise BlockNotFoundError(subject_ref)
        lifted = _lifted_from_previous(previous, reason_code)
        self.publisher.publish(_block_lifted_envelope(lifted, correlation_id, prefixed_id("cmd")))
        self.repository.remove_block(subject_ref)
        if previous.scope == BlockScope.ORDER.value:
            account_id = self.repository.account_for_order(subject_ref)
            if account_id is not None:
                self.repository.record_account_lift(account_id, _coerce_datetime(lifted.allowedAt))
        return lifted

    def _assess_journey_order_created(self, envelope: EventEnvelope) -> tuple[RiskAssessmentResult, RiskBlockApplied | None]:
        payload = envelope.payload
        order_id = _required_payload_text(payload, "orderId")
        account_id = _required_payload_text(payload, "accountId")
        assessment_id = deterministic_prefixed_id("asmt", envelope.eventId, "assessment")
        existing = self.repository.find(assessment_id)
        if existing is not None:
            block = _block_from_result(existing) if existing.decision in BLOCKING_DECISION_VALUES else None
            return existing, block
        self.repository.remember_order_account(order_id, account_id)
        occurred_at = _coerce_datetime(envelope.occurredAt)
        context = dict(payload)
        context["orderAttemptCount10m"] = self.repository.record_order_attempt(account_id, occurred_at)
        assessment = assess_risk(
            assessment_id=assessment_id,
            subject_ref=order_id,
            scenario="order_risk",
            input_data=context,
            idempotency_key=envelope.eventId,
        )
        completed = _evaluate(assessment)
        result = _to_result(completed)
        block = _block_from_result(result) if completed.decision in BLOCKING_DECISIONS else None
        return result, block


def prefixed_id(prefix: str) -> str:
    return new_prefixed_uuid7(prefix)


def deterministic_uuid(value: str) -> str:
    uuid = uuid5(NAMESPACE_URL, f"train-ticket:risk-compliance:{value}")
    uuid_int = (uuid.int & ~(0xF << 76)) | (0x7 << 76)
    return str(uuid.__class__(int=uuid_int))


def deterministic_prefixed_id(prefix: str, *parts: str) -> str:
    return f"{prefix}-{deterministic_uuid(':'.join(parts))}"


def deterministic_event_id(fact_id: str) -> str:
    return deterministic_prefixed_id("evt", fact_id, "event")


def uuid7() -> str:
    return new_uuid7()


def prefixed_uuid7(prefix: str) -> str:
    return new_prefixed_uuid7(prefix)


def _evaluate(assessment: RiskAssessment) -> RiskAssessment:
    score = _score(assessment)
    if score >= 850:
        return assessment.deny(
            policy_version=DEFAULT_POLICY_VERSION,
            reason_code="HIGH_RISK_SIGNAL",
            reason_explanation="Risk score exceeded deny threshold",
            score=score,
            level=RiskLevel.CRITICAL,
        )
    if score >= 600:
        return assessment.challenge(
            policy_version=DEFAULT_POLICY_VERSION,
            reason_code="ADDITIONAL_VERIFICATION_REQUIRED",
            reason_explanation="Additional verification is required before continuing",
            score=score,
            level=RiskLevel.HIGH,
        )
    if score >= 400:
        return assessment.allow(
            decision=Decision.HOLD,
            policy_version=DEFAULT_POLICY_VERSION,
            reason_code="MANUAL_REVIEW_REQUIRED",
            reason_explanation="Assessment is held for manual review",
            score=score,
            level=RiskLevel.MEDIUM,
        )
    return assessment.allow(
        decision=Decision.ALLOW,
        policy_version=DEFAULT_POLICY_VERSION,
        reason_code="PASSED_RISK_CHECKS",
        reason_explanation="All configured risk checks passed",
        score=score,
        level=RiskLevel.LOW,
    )


def _score(assessment: RiskAssessment) -> int:
    context = assessment.input_snapshot.input_data
    explicit = context["riskScore"] if "riskScore" in context else context.get("score")
    if isinstance(explicit, int) and 0 <= explicit <= 1000:
        return explicit
    if _has_blacklisted_document(context):
        return 950
    attempt_count = context.get("orderAttemptCount10m")
    if isinstance(attempt_count, int) and attempt_count >= FREQUENCY_THRESHOLD:
        return 900
    digest = assessment.input_snapshot.digest
    return int(digest[:8], 16) % 350


def _has_blacklisted_document(context: Mapping[str, Any]) -> bool:
    document_refs = _document_refs(context)
    if any("BLACKLIST" in ref.upper() or "***9999" in ref.upper() for ref in document_refs):
        return True
    configured = context.get("blacklistedDocumentRefs")
    if isinstance(configured, list):
        blacklist = {str(value) for value in configured}
        return any(ref in blacklist for ref in document_refs)
    return False


def _document_refs(context: Mapping[str, Any]) -> list[str]:
    refs: list[str] = []
    travelers = context.get("travelerRefs")
    if isinstance(travelers, list):
        for traveler in travelers:
            if isinstance(traveler, Mapping):
                for field_name in ("maskedDocumentRef", "maskedDocumentNo", "documentRef"):
                    value = traveler.get(field_name)
                    if isinstance(value, str) and value.strip():
                        refs.append(value)
    for field_name in ("maskedDocumentRef", "maskedDocumentNo", "documentRef"):
        value = context.get(field_name)
        if isinstance(value, str) and value.strip():
            refs.append(value)
    return refs


def _required_payload_text(payload: Mapping[str, Any], field_name: str) -> str:
    value = payload.get(field_name)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"JourneyOrderCreated payload missing {field_name}")
    return value


def _coerce_datetime(value: datetime | str) -> datetime:
    if isinstance(value, datetime):
        return value if value.tzinfo is not None else value.replace(tzinfo=UTC)
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


def _result_from_response_body(body: Any) -> RiskAssessmentResult:
    if not isinstance(body, Mapping):
        raise ValueError("stored idempotency response body must be an object")
    return RiskAssessmentResult(
        assessmentId=str(body["assessmentId"]),
        subjectRef=str(body["subjectRef"]),
        scenario=str(body["scenario"]),
        decision=str(body["decision"]),
        score=int(body["score"]),
        level=str(body["level"]),
        policyVersion=str(body["policyVersion"]),
        reasonCode=str(body["reasonCode"]),
        reasonExplanation=str(body.get("reasonExplanation", "")),
        assessedAt=str(body["assessedAt"]),
        evidenceRef=str(body.get("evidenceRef", f"evid-{body['assessmentId']}")),
        assessmentSnapshotHash=str(body.get("assessmentSnapshotHash", "")),
    )


def _to_result(assessment: RiskAssessment) -> RiskAssessmentResult:
    if assessment.decision is None or assessment.score is None or assessment.level is None:
        raise ValueError("assessment has not been completed")
    if assessment.policy_version is None or assessment.assessed_at is None or assessment.reason_code is None:
        raise ValueError("assessment is missing result details")
    return RiskAssessmentResult(
        assessmentId=assessment.assessment_id,
        subjectRef=assessment.subject_ref,
        scenario=assessment.scenario,
        decision=assessment.decision.value,
        score=assessment.score,
        level=assessment.level.value,
        policyVersion=assessment.policy_version.version,
        reasonCode=assessment.reason_code,
        reasonExplanation=assessment.reason_explanation or "",
        assessedAt=rfc3339_utc(assessment.assessed_at),
        evidenceRef=assessment.evidence_bundle.bundle_id if assessment.evidence_bundle is not None else f"evid-{assessment.assessment_id}",
        assessmentSnapshotHash=assessment.input_snapshot.digest,
    )


def _assessment_envelope(
    result: RiskAssessmentResult,
    correlation_id: str,
    causation_id: str,
    *,
    event_id: str | None = None,
) -> EventEnvelope:
    return envelope_factory(
        event_type="RiskAssessmentResult",
        producer=PRODUCER,
        payload=result.to_event_payload(),
        correlation_id=correlation_id,
        causation_id=causation_id,
        occurred_at=result.assessedAt,
        schema_version=SCHEMA_VERSION,
        event_id=event_id,
    )


def _block_from_result(result: RiskAssessmentResult) -> RiskBlockApplied:
    decision = block_subject(
        decision_id=deterministic_prefixed_id("blk", result.assessmentId, "block"),
        subject_ref=result.subjectRef,
        scope=BlockScope.ORDER,
        reason_code=result.reasonCode,
        policy_version=PolicyVersionRef("risk-rules", result.policyVersion),
        assessment_id=result.assessmentId,
        input_snapshot_digest=result.assessmentSnapshotHash,
    )
    return RiskBlockApplied(
        blockId=decision.decision_id,
        subjectRef=decision.subject_ref,
        scope=decision.scope.value,
        reasonCode=decision.reason_code,
        policyVersion=decision.policy_version.version,
        evidenceRef=result.evidenceRef,
        blockedAt=result.assessedAt,
    )


def _lifted_from_previous(previous: RiskBlockApplied, reason_code: str) -> RiskBlockLifted:
    decision = allow_subject(
        decision_id=prefixed_id("alw"),
        subject_ref=previous.subjectRef,
        scope=BlockScope(previous.scope),
        reason_code=reason_code,
        policy_version=PolicyVersionRef("risk-rules", previous.policyVersion),
        assessment_id=previous.blockId,
        input_snapshot_digest=previous.blockId,
    )
    return RiskBlockLifted(
        allowId=decision.decision_id,
        subjectRef=decision.subject_ref,
        scope=decision.scope.value,
        reasonCode=decision.reason_code,
        policyVersion=decision.policy_version.version,
        evidenceRef=previous.evidenceRef,
        allowedAt=rfc3339_utc(decision.decided_at),
    )


def _block_applied_envelope(block: RiskBlockApplied, correlation_id: str, causation_id: str) -> EventEnvelope:
    return envelope_factory(
        event_type="RiskBlockApplied",
        producer=PRODUCER,
        payload=block.to_event_payload(),
        correlation_id=correlation_id,
        causation_id=causation_id,
        occurred_at=block.blockedAt,
        schema_version=SCHEMA_VERSION,
        event_id=deterministic_event_id(block.blockId),
    )


def _block_lifted_envelope(lifted: RiskBlockLifted, correlation_id: str, causation_id: str) -> EventEnvelope:
    return envelope_factory(
        event_type="RiskBlockLifted",
        producer=PRODUCER,
        payload=lifted.to_event_payload(),
        correlation_id=correlation_id,
        causation_id=causation_id,
        occurred_at=lifted.allowedAt,
        schema_version=SCHEMA_VERSION,
    )
