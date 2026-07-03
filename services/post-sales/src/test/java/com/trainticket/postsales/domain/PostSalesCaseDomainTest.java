package com.trainticket.postsales.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostSalesCaseDomainTest {
    private static final Instant NOW = Instant.parse("2026-07-03T10:15:30Z");

    @Test
    void refundLifecycleOrdersDecisionVoidCancellationCapacityRefundAndApplicationFacts() {
        PostSalesCase postSalesCase = refundCase();
        postSalesCase.beginEvaluation(NOW.plusSeconds(1), "evaluate", "request", "corr");
        postSalesCase.recordDecision(refundDecision(postSalesCase.caseId(), true), false, false, NOW.plusSeconds(2), "quote", "evaluate", "corr");
        assertEquals(PostSalesCaseStatus.QUOTED, postSalesCase.status());

        postSalesCase.approve("auto-approval", NOW.plusSeconds(3), "approve", "quote", "corr");
        postSalesCase.requestExecution(NOW.plusSeconds(4), "execute", "approve", "corr");

        assertEquals(PostSalesCaseStatus.EXECUTING, postSalesCase.status());
        assertEquals(List.of(
            PostSalesStepType.DECISION_CONFIRMED,
            PostSalesStepType.VOID_ENTITLEMENT,
            PostSalesStepType.CANCEL_SEGMENT,
            PostSalesStepType.RELEASE_CAPACITY,
            PostSalesStepType.REQUEST_REFUND,
            PostSalesStepType.APPLY_RESULT
        ), postSalesCase.executionPlan().stream().map(PostSalesStep::type).toList());

        for (PostSalesStepType stepType : List.of(
            PostSalesStepType.DECISION_CONFIRMED,
            PostSalesStepType.VOID_ENTITLEMENT,
            PostSalesStepType.CANCEL_SEGMENT,
            PostSalesStepType.RELEASE_CAPACITY,
            PostSalesStepType.REQUEST_REFUND,
            PostSalesStepType.APPLY_RESULT
        )) {
            postSalesCase.recordStepSucceeded(stepType, "external-" + stepType, NOW.plusSeconds(10 + stepType.ordinal()), "step", "execute", "corr");
        }

        assertEquals(PostSalesCaseStatus.APPLIED, postSalesCase.status());
        assertInstanceOf(PostSalesRequested.class, postSalesCase.domainEvents().get(0));
        assertTrue(postSalesCase.domainEvents().stream().anyMatch(PostSalesEvaluated.class::isInstance));
        assertTrue(postSalesCase.domainEvents().stream().anyMatch(PostSalesApproved.class::isInstance));
        assertTrue(postSalesCase.domainEvents().stream().anyMatch(PostSalesApplied.class::isInstance));
    }

    @Test
    void refundFailureAfterEntitlementVoidRequiresManualReviewWithoutSilentTicketRestore() {
        PostSalesCase postSalesCase = approvedExecutingRefundCase();
        postSalesCase.recordStepSucceeded(PostSalesStepType.DECISION_CONFIRMED, "fare-eval", NOW.plusSeconds(5), "step", "execute", "corr");
        postSalesCase.recordStepSucceeded(PostSalesStepType.VOID_ENTITLEMENT, "voided-ticket", NOW.plusSeconds(6), "step", "execute", "corr");
        postSalesCase.recordStepSucceeded(PostSalesStepType.CANCEL_SEGMENT, "segment-cancelled", NOW.plusSeconds(7), "step", "execute", "corr");
        postSalesCase.recordStepSucceeded(PostSalesStepType.RELEASE_CAPACITY, "capacity-released", NOW.plusSeconds(8), "step", "execute", "corr");

        postSalesCase.recordStepFailed(PostSalesStepType.REQUEST_REFUND, "payment refund failed after entitlement void", true, NOW.plusSeconds(9), "refund-failed", "payment", "corr");

        assertEquals(PostSalesCaseStatus.MANUAL_REVIEW_REQUIRED, postSalesCase.status());
        assertTrue(postSalesCase.terminalReason().contains("REQUEST_REFUND"));
        assertEquals(PostSalesStepStatus.SUCCEEDED, statusOf(postSalesCase, PostSalesStepType.VOID_ENTITLEMENT));
        assertEquals(PostSalesStepStatus.MANUAL_REQUIRED, statusOf(postSalesCase, PostSalesStepType.REQUEST_REFUND));
        assertTrue(postSalesCase.domainEvents().stream().anyMatch(PostSalesManualReviewRequired.class::isInstance));
    }

    @Test
    void changeExecutionPlanIsConservativeAndIncludesHoldFinancialVoidIssueReleaseOrdering() {
        PostSalesCase postSalesCase = PostSalesCase.request("order-1", PostSalesCaseType.CHANGE, scope(), "CHANGE_DATE", "traveler-1", "idem-change", NOW, "request", "corr");
        postSalesCase.beginEvaluation(NOW.plusSeconds(1), "evaluate", "request", "corr");
        postSalesCase.recordDecision(changeDecision(postSalesCase.caseId(), ChangeFinancialAction.COLLECT_DIFFERENCE_PAYMENT), true, false, NOW.plusSeconds(2), "quote", "evaluate", "corr");
        postSalesCase.approve("user-confirmed", NOW.plusSeconds(3), "approve", "quote", "corr");
        postSalesCase.requestExecution(NOW.plusSeconds(4), "execute", "approve", "corr");

        assertEquals(List.of(
            PostSalesStepType.HOLD_REPLACEMENT_CAPACITY,
            PostSalesStepType.REQUEST_DIFFERENCE_PAYMENT,
            PostSalesStepType.VOID_OLD_ENTITLEMENT,
            PostSalesStepType.ISSUE_NEW_ENTITLEMENT,
            PostSalesStepType.RELEASE_OLD_CAPACITY,
            PostSalesStepType.APPLY_RESULT
        ), postSalesCase.executionPlan().stream().map(PostSalesStep::type).toList());

        assertThrows(DomainRuleViolation.class, () ->
            postSalesCase.recordStepSucceeded(PostSalesStepType.VOID_OLD_ENTITLEMENT, "void", NOW.plusSeconds(5), "step", "execute", "corr"));
    }

    @Test
    void decisionSnapshotsAreImmutableExplicitFarePricingReferences() {
        RuleEvaluationSnapshot snapshot = ruleSnapshot();
        PostSalesDecision decision = refundDecision("case-1", true);

        assertEquals("fare-eval-1", snapshot.farePricingEvaluationRef());
        assertEquals("rule-v2026", snapshot.fareRuleVersion());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.inputSnapshotRefs().put("order", "changed"));
        assertEquals("fare-eval-1", decision.ruleSnapshot().farePricingEvaluationRef());
        assertThrows(DomainRuleViolation.class, () -> new AmountDecisionSnapshot(
            Money.of("1.00", "USD"), Money.of("2.00", "USD"), Money.of("3.00", "USD"), "bad simultaneous money movement"));
    }

    @Test
    void ineligibleDecisionRejectsBeforeExecution() {
        PostSalesCase postSalesCase = refundCase();
        postSalesCase.beginEvaluation(NOW.plusSeconds(1), "evaluate", "request", "corr");
        postSalesCase.recordDecision(refundDecision(postSalesCase.caseId(), false), false, false, NOW.plusSeconds(2), "quote", "evaluate", "corr");

        assertEquals(PostSalesCaseStatus.REJECTED, postSalesCase.status());
        assertThrows(DomainRuleViolation.class, () -> postSalesCase.requestExecution(NOW.plusSeconds(3), "execute", "quote", "corr"));
    }

    private static PostSalesCase approvedExecutingRefundCase() {
        PostSalesCase postSalesCase = refundCase();
        postSalesCase.beginEvaluation(NOW.plusSeconds(1), "evaluate", "request", "corr");
        postSalesCase.recordDecision(refundDecision(postSalesCase.caseId(), true), false, false, NOW.plusSeconds(2), "quote", "evaluate", "corr");
        postSalesCase.approve("auto", NOW.plusSeconds(3), "approve", "quote", "corr");
        postSalesCase.requestExecution(NOW.plusSeconds(4), "execute", "approve", "corr");
        return postSalesCase;
    }

    private static PostSalesCase refundCase() {
        return PostSalesCase.request("order-1", PostSalesCaseType.REFUND, scope(), "CUSTOMER_REQUEST", "traveler-1", "idem-refund", NOW, "request", "corr");
    }

    private static PostSalesScope scope() {
        return PostSalesScope.ticket("item-1", "segment-1", "traveler-1", "entitlement-1");
    }

    private static PostSalesDecision refundDecision(String caseId, boolean eligible) {
        return PostSalesDecision.refund(caseId, eligible, eligible ? "REFUNDABLE" : "RULE_BLOCKED", ruleSnapshot(),
            AmountDecisionSnapshot.refund(Money.of("5.00", "USD"), Money.of("45.00", "USD"), "Fare & Pricing refund rule result"),
            NOW.plusSeconds(2), NOW.plusSeconds(600));
    }

    private static PostSalesDecision changeDecision(String caseId, ChangeFinancialAction financialAction) {
        return PostSalesDecision.change(caseId, true, "CHANGE_ALLOWED", ruleSnapshot(),
            AmountDecisionSnapshot.extraCharge(Money.of("0.00", "USD"), Money.of("10.00", "USD"), "Fare & Pricing change difference result"),
            new ChangeFlowSnapshot("change-offer-1", "hold-new-seat-1", financialAction, "void-old-ticket-1", "issue-new-ticket-1", "release-old-seat-1"),
            NOW.plusSeconds(2), NOW.plusSeconds(600));
    }

    private static RuleEvaluationSnapshot ruleSnapshot() {
        return new RuleEvaluationSnapshot("fare-eval-1", "offer-rule-snapshot-1", "rule-v2026", NOW,
            Map.of("orderSnapshot", "order-snapshot-1", "entitlementStatus", "ticket-issued", "farePricing", "explicit-reference-only"));
    }

    private static PostSalesStepStatus statusOf(PostSalesCase postSalesCase, PostSalesStepType type) {
        return postSalesCase.executionPlan().stream().filter(step -> step.type() == type).findFirst().orElseThrow().status();
    }
}
