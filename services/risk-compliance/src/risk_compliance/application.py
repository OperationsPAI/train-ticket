from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import Any, TypeAlias

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

from .domain import Decision, PolicyVersionRef, RiskAssessment, RiskLevel, assess_risk

PRODUCER = "risk-compliance"
SCHEMA_VERSION = 1
DEFAULT_POLICY_VERSION = PolicyVersionRef(policy_set_id="risk-rules", version="1.0.0")
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


class AssessmentNotFoundError(KeyError):
    pass


class InMemoryAssessmentRepository:
    def __init__(self) -> None:
        self._assessments: dict[str, RiskAssessmentResult] = {}

    def get(self, assessment_id: str) -> RiskAssessmentResult:
        try:
            return self._assessments[assessment_id]
        except KeyError as exc:
            raise AssessmentNotFoundError(assessment_id) from exc

    def save(self, assessment: RiskAssessmentResult) -> None:
        self._assessments[assessment.assessmentId] = assessment


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


def prefixed_id(prefix: str) -> str:
    return new_prefixed_uuid7(prefix)


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
    digest = assessment.input_snapshot.digest
    return int(digest[:8], 16) % 350


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


def _assessment_envelope(result: RiskAssessmentResult, correlation_id: str, causation_id: str) -> EventEnvelope:
    return envelope_factory(
        event_type="RiskAssessed",
        producer=PRODUCER,
        payload=result.to_event_payload(),
        correlation_id=correlation_id,
        causation_id=causation_id,
        occurred_at=result.assessedAt,
        schema_version=SCHEMA_VERSION,
    )
