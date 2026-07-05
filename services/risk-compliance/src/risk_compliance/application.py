from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime
from hashlib import sha256
from secrets import randbits
from time import time
from typing import Any, Protocol, TypeAlias
from uuid import UUID

from .domain import Decision, PolicyVersionRef, RiskAssessment, RiskLevel, assess_risk

PRODUCER = "risk-compliance"
SCHEMA_VERSION = 1
DEFAULT_POLICY_VERSION = PolicyVersionRef(policy_set_id="risk-rules", version="1.0.0")
RiskScenario: TypeAlias = str


class PublishFailed(RuntimeError):
    """Raised when an event cannot be published after adapter retries."""


class SubscribeFailed(RuntimeError):
    """Raised when an event subscriber cannot start or poll its source."""


class HandlerError(RuntimeError):
    """Base class for subscriber handler failures."""


class TransientHandlerError(HandlerError):
    """A retryable event handler failure; the message must not be acked."""


class FatalHandlerError(HandlerError):
    """A non-retryable event handler failure; the message should be sent to DLQ."""


@dataclass(frozen=True, slots=True)
class EventEnvelope:
    eventId: str
    eventType: str
    occurredAt: str
    correlationId: str
    causationId: str | None
    producer: str
    schemaVersion: int
    payload: Mapping[str, Any]

    def to_dict(self) -> dict[str, Any]:
        envelope = {
            "eventId": self.eventId,
            "eventType": self.eventType,
            "occurredAt": self.occurredAt,
            "correlationId": self.correlationId,
            "producer": self.producer,
            "schemaVersion": self.schemaVersion,
            "payload": dict(self.payload),
        }
        if self.causationId is not None:
            envelope["causationId"] = self.causationId
        return envelope

    @classmethod
    def from_mapping(cls, data: Mapping[str, Any]) -> EventEnvelope:
        return cls(
            eventId=str(data["eventId"]),
            eventType=str(data["eventType"]),
            occurredAt=str(data["occurredAt"]),
            correlationId=str(data["correlationId"]),
            causationId=str(data["causationId"]) if "causationId" in data else None,
            producer=str(data["producer"]),
            schemaVersion=int(data["schemaVersion"]),
            payload=dict(data["payload"]),
        )


class EventPublisher(Protocol):
    """Abstract event publishing port defined by docs/08-contracts/messaging.md."""

    def publish(self, envelope: EventEnvelope) -> None:
        """Publish a fully-populated EventEnvelope."""


EventHandler: TypeAlias = Callable[[EventEnvelope], None]


class EventSubscriber(Protocol):
    """Abstract event subscriber port defined by docs/08-contracts/messaging.md."""

    def subscribe(
        self,
        streams: list[str],
        group: str,
        consumerName: str,
        handler: EventHandler,
    ) -> None:
        """Subscribe to event streams as a consumer group member."""


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
class IdempotencyRecord:
    request_hash: str
    body: dict[str, Any]


@dataclass(frozen=True, slots=True)
class PendingPublication:
    request_hash: str
    body: dict[str, Any]
    envelope: EventEnvelope


class IdempotencyKeyReusedError(ValueError):
    pass


class AssessmentNotFoundError(KeyError):
    pass


class InMemoryAssessmentRepository:
    def __init__(self) -> None:
        self._assessments: dict[str, RiskAssessmentResult] = {}
        self._idempotency: dict[str, IdempotencyRecord] = {}
        self._pending_publications: dict[str, PendingPublication] = {}

    def get(self, assessment_id: str) -> RiskAssessmentResult:
        try:
            return self._assessments[assessment_id]
        except KeyError as exc:
            raise AssessmentNotFoundError(assessment_id) from exc

    def save(self, assessment: RiskAssessmentResult) -> None:
        self._assessments[assessment.assessmentId] = assessment

    def get_replay(self, idempotency_key: str, request_hash: str) -> IdempotencyRecord | None:
        record = self._idempotency.get(idempotency_key)
        if record is None:
            return None
        if record.request_hash != request_hash:
            raise IdempotencyKeyReusedError("Idempotency-Key was reused with a different request body")
        return record

    def get_pending_publication(self, idempotency_key: str, request_hash: str) -> PendingPublication | None:
        pending = self._pending_publications.get(idempotency_key)
        if pending is None:
            return None
        if pending.request_hash != request_hash:
            raise IdempotencyKeyReusedError("Idempotency-Key was reused with a different request body")
        return pending

    def save_pending_publication(self, idempotency_key: str, pending: PendingPublication) -> None:
        self._pending_publications[idempotency_key] = pending

    def save_replay(self, idempotency_key: str, record: IdempotencyRecord) -> None:
        self._pending_publications.pop(idempotency_key, None)
        self._idempotency[idempotency_key] = record


class InMemoryEventPublisher:
    def __init__(self) -> None:
        self.envelopes: list[EventEnvelope] = []

    def publish(self, envelope: EventEnvelope) -> None:
        self.envelopes.append(envelope)


class ConsumedEventDeduplicator:
    def __init__(self) -> None:
        self._seen: set[str] = set()

    def handle_once(self, envelope: EventEnvelope, handler: EventHandler) -> bool:
        if envelope.eventId in self._seen:
            return False
        handler(envelope)
        self._seen.add(envelope.eventId)
        return True

    def seen(self, event_id: str) -> bool:
        return event_id in self._seen


class InMemoryEventSubscriber:
    def __init__(self, envelopes: list[EventEnvelope] | None = None) -> None:
        self.envelopes = envelopes or []
        self.deduplicator = ConsumedEventDeduplicator()

    def subscribe(
        self,
        streams: list[str],
        group: str,
        consumerName: str,
        handler: EventHandler,
    ) -> None:
        del streams, group, consumerName
        for envelope in self.envelopes:
            self.deduplicator.handle_once(envelope, handler)


@dataclass(slots=True)
class RiskComplianceService:
    publisher: EventPublisher
    repository: InMemoryAssessmentRepository = field(default_factory=InMemoryAssessmentRepository)

    def assess(
        self,
        *,
        subject_ref: str,
        scenario: RiskScenario,
        context: Mapping[str, Any],
        idempotency_key: str,
        correlation_id: str,
    ) -> tuple[RiskAssessmentResult, bool]:
        request_hash = _request_hash(subject_ref, scenario, context)
        replay = self.repository.get_replay(idempotency_key, request_hash)
        if replay is not None:
            return RiskAssessmentResult(**replay.body), True

        pending = self.repository.get_pending_publication(idempotency_key, request_hash)
        if pending is not None:
            self.publisher.publish(pending.envelope)
            self.repository.save_replay(
                idempotency_key,
                IdempotencyRecord(request_hash=request_hash, body=pending.body),
            )
            return RiskAssessmentResult(**pending.body), False

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
        replay_body = result.to_event_payload()
        self.repository.save(result)
        self.repository.save_pending_publication(
            idempotency_key,
            PendingPublication(request_hash=request_hash, body=replay_body, envelope=envelope),
        )
        self.publisher.publish(envelope)
        self.repository.save_replay(
            idempotency_key,
            IdempotencyRecord(request_hash=request_hash, body=replay_body),
        )
        return result, False

    def get_assessment(self, assessment_id: str) -> RiskAssessmentResult:
        return self.repository.get(assessment_id)


def prefixed_id(prefix: str) -> str:
    return f"{prefix}-{uuid7()}"


def uuid7() -> UUID:
    unix_ts_ms = int(time() * 1000) & ((1 << 48) - 1)
    uuid_int = unix_ts_ms << 80
    uuid_int |= 0x7 << 76
    uuid_int |= randbits(12) << 64
    uuid_int |= 0b10 << 62
    uuid_int |= randbits(62)
    return UUID(int=uuid_int)


def is_uuid7(value: str) -> bool:
    try:
        parsed = UUID(value)
    except (TypeError, ValueError, AttributeError):
        return False
    return parsed.version == 7


def prefixed_uuid7(prefix: str) -> str:
    return f"{prefix}-{uuid7()}"


def is_prefixed_uuid7(value: str, prefix: str) -> bool:
    expected = f"{prefix}-"
    return value.startswith(expected) and is_uuid7(value[len(expected):])


def datetime_to_rfc3339(value: datetime) -> str:
    aware = value if value.tzinfo is not None else value.replace(tzinfo=UTC)
    return aware.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def canonical_correlation_id(correlation_id: str) -> str:
    return correlation_id if correlation_id.startswith("corr-") else f"corr-{correlation_id}"


def _request_hash(subject_ref: str, scenario: str, context: Mapping[str, Any]) -> str:
    import json

    payload = {"subjectRef": subject_ref, "scenario": scenario, "context": context}
    return sha256(json.dumps(payload, sort_keys=True, separators=(",", ":"), default=str).encode("utf-8")).hexdigest()


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
        assessedAt=datetime_to_rfc3339(assessment.assessed_at),
        evidenceRef=assessment.evidence_bundle.bundle_id if assessment.evidence_bundle is not None else f"evid-{assessment.assessment_id}",
        assessmentSnapshotHash=assessment.input_snapshot.digest,
    )


def _assessment_envelope(result: RiskAssessmentResult, correlation_id: str, causation_id: str) -> EventEnvelope:
    return EventEnvelope(
        eventId=prefixed_id("evt"),
        eventType="RiskAssessed",
        occurredAt=result.assessedAt,
        correlationId=canonical_correlation_id(correlation_id),
        causationId=causation_id,
        producer=PRODUCER,
        schemaVersion=SCHEMA_VERSION,
        payload=result.to_event_payload(),
    )
