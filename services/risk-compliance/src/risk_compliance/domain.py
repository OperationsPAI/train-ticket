from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from enum import Enum
from hashlib import sha256
from typing import Mapping, Self


class RiskComplianceError(ValueError):
    """Raised when risk-compliance invariants are violated."""


# ── Enums ───────────────────────────────────────────────────────────────────


class Decision(str, Enum):
    ALLOW = "ALLOW"
    DENY = "DENY"
    CHALLENGE = "CHALLENGE"
    HOLD = "HOLD"


class RiskLevel(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class AssessmentStatus(str, Enum):
    REQUESTED = "REQUESTED"
    EVALUATING = "EVALUATING"
    ALLOWED = "ALLOWED"
    DENIED = "DENIED"
    CHALLENGED = "CHALLENGED"
    HELD = "HELD"
    MANUAL_REVIEW = "MANUAL_REVIEW"
    FAILED = "FAILED"
    SUPERSEDED = "SUPERSEDED"


class ChallengeType(str, Enum):
    SMS = "SMS"
    FACE_VERIFICATION = "FACE_VERIFICATION"
    PAYMENT_VERIFICATION = "PAYMENT_VERIFICATION"
    MANUAL_REVIEW = "MANUAL_REVIEW"


class ChallengeStatus(str, Enum):
    CREATED = "CREATED"
    PENDING_USER_ACTION = "PENDING_USER_ACTION"
    PASSED = "PASSED"
    FAILED = "FAILED"
    EXPIRED = "EXPIRED"
    CANCELLED = "CANCELLED"


class ChallengeOutcome(str, Enum):
    PASSED = "PASSED"
    FAILED = "FAILED"
    EXPIRED = "EXPIRED"


class BlockScope(str, Enum):
    ORDER = "ORDER"
    PAYMENT = "PAYMENT"
    ACCOUNT = "ACCOUNT"


class BlockStatus(str, Enum):
    ACTIVE = "ACTIVE"
    RELEASED = "RELEASED"
    EXPIRED = "EXPIRED"


class EvidenceType(str, Enum):
    FRAUD_SIGNAL = "fraud_signal"
    DEVICE_FINGERPRINT = "device_fingerprint"
    RULE_HIT = "rule_hit"
    EXTERNAL_LIST_MATCH = "external_list_match"
    MANUAL_REVIEW = "manual_review"
    CHALLENGE_RESULT = "challenge_result"


# ── Value Objects ────────────────────────────────────────────────────────────


@dataclass(frozen=True, slots=True)
class AssessmentInputSnapshot:
    """Immutable snapshot of the inputs used for a risk assessment."""

    subject_ref: str
    scenario: str
    input_data: Mapping[str, object] = field(default_factory=dict)
    captured_at: datetime = field(default_factory=lambda: datetime.now(UTC))

    def __post_init__(self) -> None:
        if not self.subject_ref.strip():
            raise RiskComplianceError("subject_ref is required")
        if not self.scenario.strip():
            raise RiskComplianceError("scenario is required")

    @property
    def digest(self) -> str:
        payload = f"{self.subject_ref}:{self.scenario}:{sorted(str(k)+str(v) for k, v in self.input_data.items())}"
        return sha256(payload.encode("utf-8")).hexdigest()


@dataclass(frozen=True, slots=True)
class PolicyVersionRef:
    """Reference to the policy/rule version that produced a decision."""

    policy_set_id: str
    version: str

    def __post_init__(self) -> None:
        if not self.policy_set_id.strip():
            raise RiskComplianceError("policy_set_id is required")
        if not self.version.strip():
            raise RiskComplianceError("version is required")


@dataclass(frozen=True, slots=True)
class EvidenceBundle:
    """Evidence bundle summarizing the signals and rules that led to a decision."""

    bundle_id: str
    evidence_items: tuple[EvidenceItem, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        if not self.bundle_id.strip():
            raise RiskComplianceError("bundle_id is required")

    def add_item(self, item: EvidenceItem) -> Self:
        return EvidenceBundle(
            self.bundle_id,
            self.evidence_items + (item,),
        )

    @property
    def digest(self) -> str:
        payload = "|".join(f"{e.evidence_id}:{e.evidence_type}:{e.summary}" for e in self.evidence_items)
        return sha256(payload.encode("utf-8")).hexdigest()


@dataclass(frozen=True, slots=True)
class EvidenceItem:
    evidence_id: str
    evidence_type: str
    summary: str
    recorded_at: datetime = field(default_factory=lambda: datetime.now(UTC))

    def __post_init__(self) -> None:
        if not self.evidence_id.strip():
            raise RiskComplianceError("evidence_id is required")
        if not self.evidence_type.strip():
            raise RiskComplianceError("evidence_type is required")
        if not self.summary.strip():
            raise RiskComplianceError("summary is required")


# ── Aggregate: RiskAssessment ───────────────────────────────────────────────


@dataclass(frozen=True, slots=True)
class RiskAssessment:
    """A risk assessment for a business action.

    Invariants:
    - Same subject + scenario + policyVersion + idempotencyKey produces
      semantically equivalent results.
    - Decision must reference the input snapshot and rule version.
    - Terminal states (ALLOWED, DENIED, SUPERSEDED) are immutable.
    """

    assessment_id: str
    subject_ref: str
    scenario: str
    status: AssessmentStatus
    decision: Decision | None
    score: int | None
    level: RiskLevel | None
    policy_version: PolicyVersionRef | None
    input_snapshot: AssessmentInputSnapshot
    evidence_bundle: EvidenceBundle | None
    reason_code: str | None
    reason_explanation: str | None
    assessed_at: datetime | None
    superseded_by: str | None = None
    idempotency_key: str | None = None

    def __post_init__(self) -> None:
        if not self.assessment_id.strip():
            raise RiskComplianceError("assessment_id is required")
        if not self.subject_ref.strip():
            raise RiskComplianceError("subject_ref is required")
        if not self.scenario.strip():
            raise RiskComplianceError("scenario is required")

        # Validate score range
        if self.score is not None and not (0 <= self.score <= 1000):
            raise RiskComplianceError("score must be between 0 and 1000")

        # Terminal states must have decision
        if self.status in (AssessmentStatus.ALLOWED, AssessmentStatus.DENIED):
            if self.decision is None:
                raise RiskComplianceError("terminal states require a decision")
            if self.policy_version is None:
                raise RiskComplianceError("terminal states require a policy version")
            if self.assessed_at is None:
                raise RiskComplianceError("terminal states require assessed_at")
            if self.reason_code is None:
                raise RiskComplianceError("terminal states require a reason_code")

    def is_terminal(self) -> bool:
        return self.status in (
            AssessmentStatus.ALLOWED,
            AssessmentStatus.DENIED,
            AssessmentStatus.SUPERSEDED,
        )

    def allow(self, *, decision: Decision, score: int | None = None,
              level: RiskLevel | None = None,
              policy_version: PolicyVersionRef,
              reason_code: str, reason_explanation: str | None = None,
              evidence_bundle: EvidenceBundle | None = None,
              assessed_at: datetime | None = None) -> RiskAssessment:
        if self.is_terminal():
            raise RiskComplianceError("cannot transition from terminal state")
        if decision is not Decision.ALLOW and decision is not Decision.HOLD:
            raise RiskComplianceError("allow transition requires ALLOW or HOLD decision")
        return RiskAssessment(
            assessment_id=self.assessment_id,
            subject_ref=self.subject_ref,
            scenario=self.scenario,
            status=AssessmentStatus.ALLOWED if decision is Decision.ALLOW else AssessmentStatus.HELD,
            decision=decision,
            score=score,
            level=level,
            policy_version=policy_version,
            input_snapshot=self.input_snapshot,
            evidence_bundle=evidence_bundle or self.evidence_bundle,
            reason_code=reason_code,
            reason_explanation=reason_explanation,
            assessed_at=assessed_at or datetime.now(UTC),
            idempotency_key=self.idempotency_key,
        )

    def deny(self, *, policy_version: PolicyVersionRef,
             reason_code: str, reason_explanation: str | None = None,
             score: int | None = None, level: RiskLevel | None = None,
             evidence_bundle: EvidenceBundle | None = None,
             assessed_at: datetime | None = None) -> RiskAssessment:
        if self.is_terminal():
            raise RiskComplianceError("cannot transition from terminal state")
        return RiskAssessment(
            assessment_id=self.assessment_id,
            subject_ref=self.subject_ref,
            scenario=self.scenario,
            status=AssessmentStatus.DENIED,
            decision=Decision.DENY,
            score=score,
            level=level or RiskLevel.HIGH,
            policy_version=policy_version,
            input_snapshot=self.input_snapshot,
            evidence_bundle=evidence_bundle or self.evidence_bundle,
            reason_code=reason_code,
            reason_explanation=reason_explanation,
            assessed_at=assessed_at or datetime.now(UTC),
            idempotency_key=self.idempotency_key,
        )

    def challenge(self, *, policy_version: PolicyVersionRef,
                  reason_code: str, reason_explanation: str | None = None,
                  score: int | None = None, level: RiskLevel | None = None,
                  evidence_bundle: EvidenceBundle | None = None,
                  assessed_at: datetime | None = None) -> RiskAssessment:
        if self.is_terminal():
            raise RiskComplianceError("cannot transition from terminal state")
        return RiskAssessment(
            assessment_id=self.assessment_id,
            subject_ref=self.subject_ref,
            scenario=self.scenario,
            status=AssessmentStatus.CHALLENGED,
            decision=Decision.CHALLENGE,
            score=score,
            level=level or RiskLevel.MEDIUM,
            policy_version=policy_version,
            input_snapshot=self.input_snapshot,
            evidence_bundle=evidence_bundle or self.evidence_bundle,
            reason_code=reason_code,
            reason_explanation=reason_explanation,
            assessed_at=assessed_at or datetime.now(UTC),
            idempotency_key=self.idempotency_key,
        )

    def supersede(self, superseded_by: str) -> RiskAssessment:
        if not superseded_by.strip():
            raise RiskComplianceError("superseded_by is required")
        return RiskAssessment(
            assessment_id=self.assessment_id,
            subject_ref=self.subject_ref,
            scenario=self.scenario,
            status=AssessmentStatus.SUPERSEDED,
            decision=self.decision,
            score=self.score,
            level=self.level,
            policy_version=self.policy_version,
            input_snapshot=self.input_snapshot,
            evidence_bundle=self.evidence_bundle,
            reason_code=self.reason_code,
            reason_explanation=self.reason_explanation,
            assessed_at=self.assessed_at,
            superseded_by=superseded_by,
            idempotency_key=self.idempotency_key,
        )


# ── Aggregate: Challenge ─────────────────────────────────────────────────────


@dataclass(frozen=True, slots=True)
class Challenge:
    """A challenge that must be resolved before a business action can proceed.

    Invariants:
    - Only one active challenge per business ref + challenge type at a time.
    - Challenge outcomes (PASSED/FAILED/EXPIRED) are terminal states.
    """

    challenge_id: str
    business_ref: str
    challenge_type: ChallengeType
    assessment_id: str
    status: ChallengeStatus
    issued_at: datetime
    expires_at: datetime
    resolved_at: datetime | None = None
    outcome: ChallengeOutcome | None = None
    outcome_evidence: str | None = None

    def __post_init__(self) -> None:
        if not self.challenge_id.strip():
            raise RiskComplianceError("challenge_id is required")
        if not self.business_ref.strip():
            raise RiskComplianceError("business_ref is required")
        if not self.assessment_id.strip():
            raise RiskComplianceError("assessment_id is required")
        if self.expires_at <= self.issued_at:
            raise RiskComplianceError("expires_at must be after issued_at")

    def is_active(self) -> bool:
        return self.status in (ChallengeStatus.CREATED, ChallengeStatus.PENDING_USER_ACTION)

    def is_terminal(self) -> bool:
        return self.status in (
            ChallengeStatus.PASSED,
            ChallengeStatus.FAILED,
            ChallengeStatus.EXPIRED,
            ChallengeStatus.CANCELLED,
        )

    def start(self) -> Challenge:
        if self.status is not ChallengeStatus.CREATED:
            raise RiskComplianceError("can only start a CREATED challenge")
        return Challenge(
            challenge_id=self.challenge_id,
            business_ref=self.business_ref,
            challenge_type=self.challenge_type,
            assessment_id=self.assessment_id,
            status=ChallengeStatus.PENDING_USER_ACTION,
            issued_at=self.issued_at,
            expires_at=self.expires_at,
        )

    def pass_challenge(self, *, resolved_at: datetime | None = None,
                       evidence: str | None = None) -> Challenge:
        if not self.is_active():
            raise RiskComplianceError("can only pass an active challenge")
        if self._is_expired(resolved_at or datetime.now(UTC)):
            raise RiskComplianceError("cannot pass an expired challenge")
        return Challenge(
            challenge_id=self.challenge_id,
            business_ref=self.business_ref,
            challenge_type=self.challenge_type,
            assessment_id=self.assessment_id,
            status=ChallengeStatus.PASSED,
            issued_at=self.issued_at,
            expires_at=self.expires_at,
            resolved_at=resolved_at or datetime.now(UTC),
            outcome=ChallengeOutcome.PASSED,
            outcome_evidence=evidence,
        )

    def fail_challenge(self, *, resolved_at: datetime | None = None,
                       evidence: str | None = None) -> Challenge:
        if not self.is_active():
            raise RiskComplianceError("can only fail an active challenge")
        return Challenge(
            challenge_id=self.challenge_id,
            business_ref=self.business_ref,
            challenge_type=self.challenge_type,
            assessment_id=self.assessment_id,
            status=ChallengeStatus.FAILED,
            issued_at=self.issued_at,
            expires_at=self.expires_at,
            resolved_at=resolved_at or datetime.now(UTC),
            outcome=ChallengeOutcome.FAILED,
            outcome_evidence=evidence,
        )

    def expire(self, *, at: datetime | None = None) -> Challenge:
        if not self.is_active():
            raise RiskComplianceError("can only expire an active challenge")
        return Challenge(
            challenge_id=self.challenge_id,
            business_ref=self.business_ref,
            challenge_type=self.challenge_type,
            assessment_id=self.assessment_id,
            status=ChallengeStatus.EXPIRED,
            issued_at=self.issued_at,
            expires_at=self.expires_at,
            resolved_at=at or datetime.now(UTC),
            outcome=ChallengeOutcome.EXPIRED,
        )

    def cancel(self) -> Challenge:
        if not self.is_active():
            raise RiskComplianceError("can only cancel an active challenge")
        return Challenge(
            challenge_id=self.challenge_id,
            business_ref=self.business_ref,
            challenge_type=self.challenge_type,
            assessment_id=self.assessment_id,
            status=ChallengeStatus.CANCELLED,
            issued_at=self.issued_at,
            expires_at=self.expires_at,
            resolved_at=datetime.now(UTC),
        )

    def _is_expired(self, at: datetime) -> bool:
        return at >= self.expires_at

    @classmethod
    def create(cls, *, challenge_id: str, business_ref: str,
               challenge_type: ChallengeType, assessment_id: str,
               ttl: timedelta,
               issued_at: datetime | None = None) -> Challenge:
        now = issued_at or datetime.now(UTC)
        return cls(
            challenge_id=challenge_id,
            business_ref=business_ref,
            challenge_type=challenge_type,
            assessment_id=assessment_id,
            status=ChallengeStatus.CREATED,
            issued_at=now,
            expires_at=now + ttl,
        )


# ── Aggregate: RiskDecision ──────────────────────────────────────────────────


@dataclass(frozen=True, slots=True)
class RiskDecision:
    """A block/allow decision on a subject scope.

    Invariants:
    - A Block decision prevents new transactions in the scope but never
      cancels in-flight refunds or notifications.
    - Every decision references the assessment inputs snapshot and rule version.
    """

    decision_id: str
    subject_ref: str
    scope: BlockScope
    is_block: bool
    reason_code: str
    policy_version: PolicyVersionRef
    assessment_id: str
    input_snapshot_digest: str
    evidence_bundle: EvidenceBundle | None
    decided_at: datetime
    status: BlockStatus = BlockStatus.ACTIVE

    def __post_init__(self) -> None:
        if not self.decision_id.strip():
            raise RiskComplianceError("decision_id is required")
        if not self.subject_ref.strip():
            raise RiskComplianceError("subject_ref is required")
        if not self.reason_code.strip():
            raise RiskComplianceError("reason_code is required")
        if not self.input_snapshot_digest.strip():
            raise RiskComplianceError("input_snapshot_digest is required")

    @classmethod
    def block(cls, *, decision_id: str, subject_ref: str, scope: BlockScope,
              reason_code: str, policy_version: PolicyVersionRef,
              assessment_id: str, input_snapshot_digest: str,
              evidence_bundle: EvidenceBundle | None = None,
              decided_at: datetime | None = None) -> RiskDecision:
        return cls(
            decision_id=decision_id,
            subject_ref=subject_ref,
            scope=scope,
            is_block=True,
            reason_code=reason_code,
            policy_version=policy_version,
            assessment_id=assessment_id,
            input_snapshot_digest=input_snapshot_digest,
            evidence_bundle=evidence_bundle,
            decided_at=decided_at or datetime.now(UTC),
        )

    @classmethod
    def allow(cls, *, decision_id: str, subject_ref: str, scope: BlockScope,
              reason_code: str, policy_version: PolicyVersionRef,
              assessment_id: str, input_snapshot_digest: str,
              evidence_bundle: EvidenceBundle | None = None,
              decided_at: datetime | None = None) -> RiskDecision:
        return cls(
            decision_id=decision_id,
            subject_ref=subject_ref,
            scope=scope,
            is_block=False,
            reason_code=reason_code,
            policy_version=policy_version,
            assessment_id=assessment_id,
            input_snapshot_digest=input_snapshot_digest,
            evidence_bundle=evidence_bundle,
            decided_at=decided_at or datetime.now(UTC),
        )

    def release(self) -> RiskDecision:
        if self.status is not BlockStatus.ACTIVE:
            raise RiskComplianceError("can only release an ACTIVE decision")
        return RiskDecision(
            decision_id=self.decision_id,
            subject_ref=self.subject_ref,
            scope=self.scope,
            is_block=self.is_block,
            reason_code=self.reason_code,
            policy_version=self.policy_version,
            assessment_id=self.assessment_id,
            input_snapshot_digest=self.input_snapshot_digest,
            evidence_bundle=self.evidence_bundle,
            decided_at=self.decided_at,
            status=BlockStatus.RELEASED,
        )


# ── Aggregate: EvidenceSummary ────────────────────────────────────────────────


@dataclass(frozen=True, slots=True)
class EvidenceSummary:
    """A recorded evidence fact for audit and explainability."""

    evidence_id: str
    subject_ref: str
    evidence_type: str
    summary: str
    detail: str | None = None
    recorded_at: datetime = field(default_factory=lambda: datetime.now(UTC))

    def __post_init__(self) -> None:
        if not self.evidence_id.strip():
            raise RiskComplianceError("evidence_id is required")
        if not self.subject_ref.strip():
            raise RiskComplianceError("subject_ref is required")
        if not self.evidence_type.strip():
            raise RiskComplianceError("evidence_type is required")
        if not self.summary.strip():
            raise RiskComplianceError("summary is required")


# ── Domain Commands ──────────────────────────────────────────────────────────


def assess_risk(
    *,
    assessment_id: str,
    subject_ref: str,
    scenario: str,
    input_data: Mapping[str, object] | None = None,
    idempotency_key: str | None = None,
) -> RiskAssessment:
    """Create a new risk assessment in REQUESTED state."""
    snapshot = AssessmentInputSnapshot(
        subject_ref=subject_ref,
        scenario=scenario,
        input_data=input_data or {},
    )
    return RiskAssessment(
        assessment_id=assessment_id,
        subject_ref=subject_ref,
        scenario=scenario,
        status=AssessmentStatus.REQUESTED,
        decision=None,
        score=None,
        level=None,
        policy_version=None,
        input_snapshot=snapshot,
        evidence_bundle=None,
        reason_code=None,
        reason_explanation=None,
        assessed_at=None,
        idempotency_key=idempotency_key,
    )


def issue_challenge(
    *,
    challenge_id: str,
    business_ref: str,
    challenge_type: ChallengeType,
    assessment_id: str,
    ttl: timedelta,
    issued_at: datetime | None = None,
) -> Challenge:
    """Issue a new challenge."""
    return Challenge.create(
        challenge_id=challenge_id,
        business_ref=business_ref,
        challenge_type=challenge_type,
        assessment_id=assessment_id,
        ttl=ttl,
        issued_at=issued_at,
    )


def resolve_challenge(
    challenge: Challenge,
    *,
    outcome: ChallengeOutcome,
    evidence: str | None = None,
    resolved_at: datetime | None = None,
) -> Challenge:
    """Resolve an active challenge with the given outcome."""
    if outcome is ChallengeOutcome.PASSED:
        return challenge.pass_challenge(resolved_at=resolved_at, evidence=evidence)
    elif outcome is ChallengeOutcome.FAILED:
        return challenge.fail_challenge(resolved_at=resolved_at, evidence=evidence)
    else:
        return challenge.expire(at=resolved_at)


def block_subject(
    *,
    decision_id: str,
    subject_ref: str,
    scope: BlockScope,
    reason_code: str,
    policy_version: PolicyVersionRef,
    assessment_id: str,
    input_snapshot_digest: str,
    evidence_bundle: EvidenceBundle | None = None,
) -> RiskDecision:
    """Block a subject scope."""
    return RiskDecision.block(
        decision_id=decision_id,
        subject_ref=subject_ref,
        scope=scope,
        reason_code=reason_code,
        policy_version=policy_version,
        assessment_id=assessment_id,
        input_snapshot_digest=input_snapshot_digest,
        evidence_bundle=evidence_bundle,
    )


def allow_subject(
    *,
    decision_id: str,
    subject_ref: str,
    scope: BlockScope,
    reason_code: str,
    policy_version: PolicyVersionRef,
    assessment_id: str,
    input_snapshot_digest: str,
    evidence_bundle: EvidenceBundle | None = None,
) -> RiskDecision:
    """Allow a subject scope."""
    return RiskDecision.allow(
        decision_id=decision_id,
        subject_ref=subject_ref,
        scope=scope,
        reason_code=reason_code,
        policy_version=policy_version,
        assessment_id=assessment_id,
        input_snapshot_digest=input_snapshot_digest,
        evidence_bundle=evidence_bundle,
    )


def record_evidence(
    *,
    evidence_id: str,
    subject_ref: str,
    evidence_type: str,
    summary: str,
    detail: str | None = None,
) -> EvidenceSummary:
    """Record an evidence fact."""
    return EvidenceSummary(
        evidence_id=evidence_id,
        subject_ref=subject_ref,
        evidence_type=evidence_type,
        summary=summary,
        detail=detail,
    )
