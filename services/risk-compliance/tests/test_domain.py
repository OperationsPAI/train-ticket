from __future__ import annotations

import unittest
from datetime import UTC, datetime, timedelta
from dataclasses import FrozenInstanceError

from risk_compliance import (
    AssessmentInputSnapshot,
    AssessmentStatus,
    BlockScope,
    BlockStatus,
    Challenge,
    ChallengeOutcome,
    ChallengeStatus,
    ChallengeType,
    Decision,
    EvidenceBundle,
    EvidenceItem,
    EvidenceSummary,
    PolicyVersionRef,
    RiskAssessment,
    RiskComplianceError,
    RiskDecision,
    RiskLevel,
    allow_subject,
    assess_risk,
    block_subject,
    issue_challenge,
    record_evidence,
    resolve_challenge,
)


NOW = datetime(2026, 7, 4, 12, 0, tzinfo=UTC)
POLICY_V1 = PolicyVersionRef(policy_set_id="risk-rules-v1", version="1.0.0")


def sample_input() -> AssessmentInputSnapshot:
    return AssessmentInputSnapshot(
        subject_ref="ord-abc123",
        scenario="order_risk",
        input_data={"amount": 50000, "traveler_ref": "tvl-xyz789"},
        captured_at=NOW,
    )


def sample_evidence_bundle() -> EvidenceBundle:
    return EvidenceBundle(
        bundle_id="eb-001",
        evidence_items=(
            EvidenceItem("evid-001", "fraud_signal", "Suspicious device fingerprint"),
            EvidenceItem("evid-002", "rule_hit", "Velocity check exceeded threshold"),
        ),
    )


class RiskAssessmentTest(unittest.TestCase):
    def test_assess_risk_creates_requested_state(self) -> None:
        assessment = assess_risk(
            assessment_id="asmt-001",
            subject_ref="ord-abc123",
            scenario="order_risk",
            input_data={"amount": 50000},
        )
        self.assertEqual(assessment.assessment_id, "asmt-001")
        self.assertEqual(assessment.status, AssessmentStatus.REQUESTED)
        self.assertIsNone(assessment.decision)
        self.assertIsNone(assessment.assessed_at)
        self.assertEqual(assessment.input_snapshot.subject_ref, "ord-abc123")

    def test_assess_risk_rejects_empty_id(self) -> None:
        with self.assertRaises(RiskComplianceError):
            assess_risk(assessment_id="", subject_ref="ord-001", scenario="order_risk")

    def test_allow_transition_from_requested(self) -> None:
        assessment = assess_risk(
            assessment_id="asmt-002",
            subject_ref="ord-002",
            scenario="order_risk",
        )
        allowed = assessment.allow(
            decision=Decision.ALLOW,
            score=150,
            level=RiskLevel.LOW,
            policy_version=POLICY_V1,
            reason_code="PASSED_ALL_CHECKS",
            reason_explanation="All risk checks passed",
            assessed_at=NOW,
        )
        self.assertEqual(allowed.status, AssessmentStatus.ALLOWED)
        self.assertEqual(allowed.decision, Decision.ALLOW)
        self.assertEqual(allowed.score, 150)
        self.assertEqual(allowed.level, RiskLevel.LOW)
        self.assertEqual(allowed.reason_code, "PASSED_ALL_CHECKS")
        self.assertEqual(allowed.assessed_at, NOW)

    def test_deny_transition_from_requested(self) -> None:
        assessment = assess_risk(
            assessment_id="asmt-003",
            subject_ref="ord-003",
            scenario="order_risk",
        )
        denied = assessment.deny(
            policy_version=POLICY_V1,
            reason_code="BLACKLIST_MATCH",
            reason_explanation="Subject matched blacklist",
            score=950,
            level=RiskLevel.CRITICAL,
            assessed_at=NOW,
        )
        self.assertEqual(denied.status, AssessmentStatus.DENIED)
        self.assertEqual(denied.decision, Decision.DENY)
        self.assertEqual(denied.reason_code, "BLACKLIST_MATCH")

    def test_challenge_transition_from_requested(self) -> None:
        assessment = assess_risk(
            assessment_id="asmt-004",
            subject_ref="ord-004",
            scenario="payment_risk",
        )
        challenged = assessment.challenge(
            policy_version=POLICY_V1,
            reason_code="CHALLENGE_REQUIRED",
            score=500,
            level=RiskLevel.MEDIUM,
            assessed_at=NOW,
        )
        self.assertEqual(challenged.status, AssessmentStatus.CHALLENGED)
        self.assertEqual(challenged.decision, Decision.CHALLENGE)

    def test_supersede_from_terminal_state(self) -> None:
        assessment = assess_risk(
            assessment_id="asmt-005",
            subject_ref="ord-005",
            scenario="order_risk",
        )
        allowed = assessment.allow(
            decision=Decision.ALLOW,
            policy_version=POLICY_V1,
            reason_code="ALLOWED",
            assessed_at=NOW,
        )
        superseded = allowed.supersede("asmt-005-v2")
        self.assertEqual(superseded.status, AssessmentStatus.SUPERSEDED)
        self.assertEqual(superseded.superseded_by, "asmt-005-v2")

    def test_cannot_transition_from_terminal_state(self) -> None:
        assessment = assess_risk(
            assessment_id="asmt-006",
            subject_ref="ord-006",
            scenario="order_risk",
        )
        allowed = assessment.allow(
            decision=Decision.ALLOW,
            policy_version=POLICY_V1,
            reason_code="ALLOWED",
            assessed_at=NOW,
        )
        with self.assertRaises(RiskComplianceError):
            allowed.deny(
                policy_version=POLICY_V1,
                reason_code="SHOULD_NOT_HAPPEN",
            )

    def test_terminal_state_requires_decision_and_policy_version(self) -> None:
        snapshot = sample_input()
        with self.assertRaises(RiskComplianceError):
            RiskAssessment(
                assessment_id="asmt-bad",
                subject_ref="ord-bad",
                scenario="order_risk",
                status=AssessmentStatus.ALLOWED,
                decision=None,
                score=None,
                level=None,
                policy_version=None,
                input_snapshot=snapshot,
                evidence_bundle=None,
                reason_code=None,
                reason_explanation=None,
                assessed_at=None,
            )

    def test_score_range_enforced(self) -> None:
        snapshot = sample_input()
        with self.assertRaises(RiskComplianceError):
            RiskAssessment(
                assessment_id="asmt-bad-score",
                subject_ref="ord-bad",
                scenario="order_risk",
                status=AssessmentStatus.REQUESTED,
                decision=None,
                score=1001,
                level=None,
                policy_version=None,
                input_snapshot=snapshot,
                evidence_bundle=None,
                reason_code=None,
                reason_explanation=None,
                assessed_at=None,
            )

    def test_assessment_immutability(self) -> None:
        assessment = assess_risk(
            assessment_id="asmt-immutable",
            subject_ref="ord-imm",
            scenario="order_risk",
        )
        with self.assertRaises(FrozenInstanceError):
            assessment.status = AssessmentStatus.ALLOWED  # type: ignore[misc]

    def test_input_snapshot_digest_stable(self) -> None:
        snap1 = AssessmentInputSnapshot(
            subject_ref="ord-abc",
            scenario="order_risk",
            input_data={"a": 1, "b": 2},
        )
        snap2 = AssessmentInputSnapshot(
            subject_ref="ord-abc",
            scenario="order_risk",
            input_data={"b": 2, "a": 1},  # same keys, different order
        )
        self.assertEqual(snap1.digest, snap2.digest)

    def test_hold_transition(self) -> None:
        assessment = assess_risk(
            assessment_id="asmt-hold",
            subject_ref="ord-hold",
            scenario="order_risk",
        )
        held = assessment.allow(
            decision=Decision.HOLD,
            policy_version=POLICY_V1,
            reason_code="HOLD_FOR_REVIEW",
            assessed_at=NOW,
        )
        self.assertEqual(held.status, AssessmentStatus.HELD)
        self.assertEqual(held.decision, Decision.HOLD)

    def test_allow_transition_rejects_deny(self) -> None:
        assessment = assess_risk(
            assessment_id="asmt-allow-bad",
            subject_ref="ord-bad",
            scenario="order_risk",
        )
        with self.assertRaises(RiskComplianceError):
            assessment.allow(
                decision=Decision.DENY,
                policy_version=POLICY_V1,
                reason_code="SHOULD_NOT",
                assessed_at=NOW,
            )


class ChallengeTest(unittest.TestCase):
    def test_create_challenge(self) -> None:
        challenge = issue_challenge(
            challenge_id="chg-001",
            business_ref="ord-001",
            challenge_type=ChallengeType.SMS,
            assessment_id="asmt-001",
            ttl=timedelta(minutes=15),
            issued_at=NOW,
        )
        self.assertEqual(challenge.challenge_id, "chg-001")
        self.assertEqual(challenge.status, ChallengeStatus.CREATED)
        self.assertEqual(challenge.expires_at, NOW + timedelta(minutes=15))

    def test_challenge_lifecycle(self) -> None:
        challenge = issue_challenge(
            challenge_id="chg-002",
            business_ref="ord-002",
            challenge_type=ChallengeType.SMS,
            assessment_id="asmt-002",
            ttl=timedelta(minutes=15),
            issued_at=NOW,
        )

        # Start
        started = challenge.start()
        self.assertEqual(started.status, ChallengeStatus.PENDING_USER_ACTION)

        # Pass
        passed = started.pass_challenge(resolved_at=NOW + timedelta(minutes=5))
        self.assertEqual(passed.status, ChallengeStatus.PASSED)
        self.assertEqual(passed.outcome, ChallengeOutcome.PASSED)
        self.assertIsNotNone(passed.resolved_at)

    def test_challenge_fail(self) -> None:
        challenge = issue_challenge(
            challenge_id="chg-003",
            business_ref="ord-003",
            challenge_type=ChallengeType.FACE_VERIFICATION,
            assessment_id="asmt-003",
            ttl=timedelta(minutes=10),
            issued_at=NOW,
        )
        started = challenge.start()
        failed = started.fail_challenge(resolved_at=NOW + timedelta(minutes=3))
        self.assertEqual(failed.status, ChallengeStatus.FAILED)
        self.assertEqual(failed.outcome, ChallengeOutcome.FAILED)

    def test_challenge_expire(self) -> None:
        challenge = issue_challenge(
            challenge_id="chg-004",
            business_ref="ord-004",
            challenge_type=ChallengeType.SMS,
            assessment_id="asmt-004",
            ttl=timedelta(seconds=1),
            issued_at=NOW,
        )
        started = challenge.start()
        expired = started.expire(at=NOW + timedelta(seconds=2))
        self.assertEqual(expired.status, ChallengeStatus.EXPIRED)
        self.assertEqual(expired.outcome, ChallengeOutcome.EXPIRED)

    def test_challenge_cancel(self) -> None:
        challenge = issue_challenge(
            challenge_id="chg-005",
            business_ref="ord-005",
            challenge_type=ChallengeType.MANUAL_REVIEW,
            assessment_id="asmt-005",
            ttl=timedelta(hours=24),
            issued_at=NOW,
        )
        started = challenge.start()
        cancelled = started.cancel()
        self.assertEqual(cancelled.status, ChallengeStatus.CANCELLED)

    def test_cannot_pass_unstarted_challenge(self) -> None:
        challenge = issue_challenge(
            challenge_id="chg-006",
            business_ref="ord-006",
            challenge_type=ChallengeType.SMS,
            assessment_id="asmt-006",
            ttl=timedelta(minutes=15),
            issued_at=NOW,
        )
        with self.assertRaises(RiskComplianceError):
            challenge.pass_challenge()

    def test_cannot_pass_expired_challenge(self) -> None:
        challenge = issue_challenge(
            challenge_id="chg-007",
            business_ref="ord-007",
            challenge_type=ChallengeType.SMS,
            assessment_id="asmt-007",
            ttl=timedelta(minutes=1),
            issued_at=NOW - timedelta(minutes=10),  # already expired
        )
        started = challenge.start()
        with self.assertRaises(RiskComplianceError):
            started.pass_challenge(resolved_at=NOW)

    def test_cannot_pass_terminal_challenge(self) -> None:
        challenge = issue_challenge(
            challenge_id="chg-008",
            business_ref="ord-008",
            challenge_type=ChallengeType.SMS,
            assessment_id="asmt-008",
            ttl=timedelta(minutes=15),
            issued_at=NOW,
        )
        started = challenge.start()
        passed = started.pass_challenge(resolved_at=NOW + timedelta(minutes=5))
        with self.assertRaises(RiskComplianceError):
            passed.fail_challenge()

    def test_resolve_challenge_command(self) -> None:
        challenge = issue_challenge(
            challenge_id="chg-009",
            business_ref="ord-009",
            challenge_type=ChallengeType.SMS,
            assessment_id="asmt-009",
            ttl=timedelta(minutes=15),
            issued_at=NOW,
        ).start()

        resolved = resolve_challenge(
            challenge,
            outcome=ChallengeOutcome.PASSED,
            evidence="User verified via SMS code",
            resolved_at=NOW + timedelta(minutes=3),
        )
        self.assertEqual(resolved.status, ChallengeStatus.PASSED)
        self.assertEqual(resolved.outcome_evidence, "User verified via SMS code")


class RiskDecisionTest(unittest.TestCase):
    def test_block_decision(self) -> None:
        decision = block_subject(
            decision_id="blk-001",
            subject_ref="ord-001",
            scope=BlockScope.ORDER,
            reason_code="FRAUD_SUSPECTED",
            policy_version=POLICY_V1,
            assessment_id="asmt-001",
            input_snapshot_digest=sample_input().digest,
        )
        self.assertTrue(decision.is_block)
        self.assertEqual(decision.scope, BlockScope.ORDER)
        self.assertEqual(decision.status, BlockStatus.ACTIVE)

    def test_allow_decision(self) -> None:
        decision = allow_subject(
            decision_id="alw-001",
            subject_ref="ord-002",
            scope=BlockScope.ORDER,
            reason_code="CLEARED",
            policy_version=POLICY_V1,
            assessment_id="asmt-002",
            input_snapshot_digest=sample_input().digest,
        )
        self.assertFalse(decision.is_block)
        self.assertEqual(decision.status, BlockStatus.ACTIVE)

    def test_release_block_decision(self) -> None:
        decision = block_subject(
            decision_id="blk-002",
            subject_ref="ord-003",
            scope=BlockScope.ORDER,
            reason_code="BLOCKED",
            policy_version=POLICY_V1,
            assessment_id="asmt-003",
            input_snapshot_digest=sample_input().digest,
        )
        released = decision.release()
        self.assertEqual(released.status, BlockStatus.RELEASED)

    def test_cannot_release_non_active(self) -> None:
        decision = block_subject(
            decision_id="blk-003",
            subject_ref="ord-004",
            scope=BlockScope.ORDER,
            reason_code="BLOCKED",
            policy_version=POLICY_V1,
            assessment_id="asmt-004",
            input_snapshot_digest=sample_input().digest,
        )
        released = decision.release()
        with self.assertRaises(RiskComplianceError):
            released.release()

    def test_block_subject_requires_valid_id(self) -> None:
        with self.assertRaises(RiskComplianceError):
            block_subject(
                decision_id="",
                subject_ref="ord-001",
                scope=BlockScope.ORDER,
                reason_code="TEST",
                policy_version=POLICY_V1,
                assessment_id="asmt-001",
                input_snapshot_digest="digest",
            )

    def test_block_scope_enforcement(self) -> None:
        for scope in BlockScope:
            decision = block_subject(
                decision_id=f"blk-scope-{scope.value}",
                subject_ref=f"ref-{scope.value}",
                scope=scope,
                reason_code="TEST",
                policy_version=POLICY_V1,
                assessment_id="asmt-scope",
                input_snapshot_digest="digest",
            )
            self.assertEqual(decision.scope, scope)


class EvidenceSummaryTest(unittest.TestCase):
    def test_record_evidence(self) -> None:
        evidence = record_evidence(
            evidence_id="evid-001",
            subject_ref="ord-001",
            evidence_type="fraud_signal",
            summary="Suspicious device fingerprint detected",
            detail="Device ID: abc-123, Risk Score: 850",
        )
        self.assertEqual(evidence.evidence_id, "evid-001")
        self.assertEqual(evidence.subject_ref, "ord-001")
        self.assertEqual(evidence.evidence_type, "fraud_signal")

    def test_record_evidence_rejects_empty_id(self) -> None:
        with self.assertRaises(RiskComplianceError):
            record_evidence(
                evidence_id="",
                subject_ref="ord-001",
                evidence_type="fraud_signal",
                summary="test",
            )

    def test_record_evidence_rejects_empty_summary(self) -> None:
        with self.assertRaises(RiskComplianceError):
            record_evidence(
                evidence_id="evid-002",
                subject_ref="ord-001",
                evidence_type="fraud_signal",
                summary="",
            )


class EvidenceBundleTest(unittest.TestCase):
    def test_evidence_bundle_digest(self) -> None:
        bundle = EvidenceBundle(
            bundle_id="eb-001",
            evidence_items=(
                EvidenceItem("evid-001", "fraud_signal", "Signal A"),
                EvidenceItem("evid-002", "rule_hit", "Rule B"),
            ),
        )
        self.assertTrue(bundle.digest)

    def test_evidence_bundle_add_item(self) -> None:
        bundle = EvidenceBundle(bundle_id="eb-002")
        self.assertEqual(len(bundle.evidence_items), 0)
        bundle2 = bundle.add_item(
            EvidenceItem("evid-003", "manual_review", "Reviewed by operator")
        )
        self.assertEqual(len(bundle2.evidence_items), 1)
        self.assertEqual(len(bundle.evidence_items), 0)  # immutable


class PolicyVersionRefTest(unittest.TestCase):
    def test_valid_policy_version(self) -> None:
        ref = PolicyVersionRef(policy_set_id="rules-v1", version="1.0.0")
        self.assertEqual(ref.policy_set_id, "rules-v1")
        self.assertEqual(ref.version, "1.0.0")

    def test_empty_policy_set_id(self) -> None:
        with self.assertRaises(RiskComplianceError):
            PolicyVersionRef(policy_set_id="", version="1.0.0")

    def test_empty_version(self) -> None:
        with self.assertRaises(RiskComplianceError):
            PolicyVersionRef(policy_set_id="rules-v1", version="")


class AssessmentInputSnapshotTest(unittest.TestCase):
    def test_empty_subject_ref(self) -> None:
        with self.assertRaises(RiskComplianceError):
            AssessmentInputSnapshot(subject_ref="", scenario="order_risk")

    def test_empty_scenario(self) -> None:
        with self.assertRaises(RiskComplianceError):
            AssessmentInputSnapshot(subject_ref="ord-001", scenario="")


class DomainInvariantsTest(unittest.TestCase):
    def test_block_decision_never_cancels_in_flight(self) -> None:
        """A Block decision should be scoped and not cancel in-flight refunds/notifications.

        This is a domain invariant test: the RiskDecision only records the block fact.
        It doesn't have side effects on refunds or notifications.
        """
        decision = block_subject(
            decision_id="blk-invariant",
            subject_ref="ord-inflight",
            scope=BlockScope.ORDER,
            reason_code="FRAUD_SUSPECTED",
            policy_version=POLICY_V1,
            assessment_id="asmt-inflight",
            input_snapshot_digest=sample_input().digest,
        )
        self.assertTrue(decision.is_block)
        self.assertEqual(decision.scope, BlockScope.ORDER)
        # The decision itself does NOT cancel refunds or notifications
        # It's a fact that other domains consume and act upon
        self.assertEqual(decision.status, BlockStatus.ACTIVE)

    def test_decision_references_assessment_input_and_rule_version(self) -> None:
        """Every decision must reference assessment input snapshot and policy version."""
        decision = allow_subject(
            decision_id="alw-ref",
            subject_ref="ord-ref",
            scope=BlockScope.ORDER,
            reason_code="CLEARED",
            policy_version=POLICY_V1,
            assessment_id="asmt-ref",
            input_snapshot_digest=sample_input().digest,
        )
        self.assertEqual(decision.policy_version, POLICY_V1)
        self.assertEqual(decision.assessment_id, "asmt-ref")
        self.assertTrue(decision.input_snapshot_digest)

    def test_challenge_outcomes_appended_as_facts(self) -> None:
        """Challenge outcomes are recorded as evidence facts."""
        # Create and resolve a challenge
        challenge = issue_challenge(
            challenge_id="chg-fact",
            business_ref="ord-fact",
            challenge_type=ChallengeType.SMS,
            assessment_id="asmt-fact",
            ttl=timedelta(minutes=15),
            issued_at=NOW,
        ).start()

        passed = challenge.pass_challenge(resolved_at=NOW + timedelta(minutes=3), evidence="SMS code verified")
        self.assertEqual(passed.outcome, ChallengeOutcome.PASSED)
        self.assertEqual(passed.outcome_evidence, "SMS code verified")

        # The outcome is a fact recorded on the challenge
        # This can be used to create an EvidenceSummary for audit
        evidence = record_evidence(
            evidence_id="evid-challenge-result",
            subject_ref="ord-fact",
            evidence_type="challenge_result",
            summary=f"Challenge {passed.challenge_id} outcome: {passed.outcome.value}",
            detail=passed.outcome_evidence,
        )
        self.assertEqual(evidence.evidence_type, "challenge_result")


if __name__ == "__main__":
    unittest.main()
