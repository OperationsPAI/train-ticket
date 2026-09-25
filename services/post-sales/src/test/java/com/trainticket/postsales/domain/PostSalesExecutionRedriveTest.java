package com.trainticket.postsales.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostSalesExecutionRedriveTest {
    private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");

    @Test
    void executingCaseBuildsOneStableApprovalAndExecutionEventPair() {
        PostSalesCase source = executingRefund();

        assertTrue(source.prepareExecutionRedrive(NOW.plusSeconds(10), "cmd-redrive", "cmd-redrive", "corr-redrive"));
        List<PostSalesEvent> first = source.executionRedriveEvents();
        assertFalse(source.prepareExecutionRedrive(NOW.plusSeconds(20), "cmd-another", "cmd-another", "corr-another"));

        assertEquals(first, source.executionRedriveEvents());
        assertEquals(List.of(PostSalesApproved.class, PostSalesExecutionStarted.class),
            first.stream().map(Object::getClass).toList());
        assertEquals("true", first.getFirst().metadata().attributes().get("redrive"));
        assertEquals("true", first.getLast().metadata().attributes().get("redrive"));
    }

    @Test
    void appliedCaseCannotPrepareExecutionRedrive() {
        PostSalesCase source = executingRefund();
        source.applyResult("done", NOW.plusSeconds(1), "cmd-apply", "cmd-apply", "corr-apply");

        assertThrows(DomainRuleViolation.class,
            () -> source.prepareExecutionRedrive(NOW.plusSeconds(2), "cmd-redrive", "cmd-redrive", "corr-redrive"));
    }

    @Test
    void authoritativeEvidenceAccountsForThePersistedPlanAndAppliesTheCase() {
        PostSalesCase source = executingRefund();
        long approved = source.decision().amountSnapshot().refundAmount().toMinorUnits();
        PostSalesExecutionEvidence evidence = evidence(source, approved);
        int priorEvents = source.domainEvents().size();

        assertTrue(source.reconcileCompletedExecution(evidence, NOW.plusSeconds(20),
            "cmd-reconcile", "cmd-reconcile", "corr-reconcile"));

        assertEquals(PostSalesCaseStatus.APPLIED, source.status());
        assertEquals("authoritative downstream execution reconciled", source.terminalReason());
        assertTrue(source.executionPlan().stream().allMatch(step -> step.status() == PostSalesStepStatus.SUCCEEDED));
        assertEquals(java.util.Set.of("sb-redrive"), source.releasedOrderItems());
        assertEquals(7, source.domainEvents().size() - priorEvents);
        assertTrue(source.domainEvents().stream().anyMatch(PostSalesApplied.class::isInstance));
        assertFalse(source.reconcileCompletedExecution(evidence, NOW.plusSeconds(21),
            "cmd-reconcile-again", "cmd-reconcile-again", "corr-reconcile"));
    }

    @Test
    void aggregateRejectsEvidenceWithAnUnrelatedCapacityHold() {
        PostSalesCase source = executingRefund();
        long approved = source.decision().amountSnapshot().refundAmount().toMinorUnits();
        PostSalesExecutionEvidence valid = evidence(source, approved);
        PostSalesExecutionEvidence mismatched = new PostSalesExecutionEvidence(
            valid.caseId(), valid.journeyOrderId(), valid.entitlements(), valid.bookings(),
            List.of(new PostSalesExecutionEvidence.CapacityEvidence("hold-other", "sb-redrive", true)),
            valid.refunds());

        assertThrows(DomainRuleViolation.class, () -> source.reconcileCompletedExecution(mismatched,
            NOW.plusSeconds(20), "cmd-reconcile", "cmd-reconcile", "corr-reconcile"));
        assertEquals(PostSalesCaseStatus.EXECUTING, source.status());
    }

    private static PostSalesExecutionEvidence evidence(PostSalesCase source, long approvedMinorUnits) {
        return new PostSalesExecutionEvidence(source.caseId(), source.journeyOrderId(),
            List.of(new PostSalesExecutionEvidence.EntitlementEvidence(
                "ent-redrive", "sb-redrive", "tvl-redrive", "seg-redrive", NOW.plusSeconds(3))),
            List.of(new PostSalesExecutionEvidence.BookingEvidence(
                "sb-redrive", "ent-redrive", "tvl-redrive", "seg-redrive", "hold-redrive")),
            List.of(new PostSalesExecutionEvidence.CapacityEvidence("hold-redrive", "sb-redrive", true)),
            List.of(new PostSalesExecutionEvidence.RefundEvidence(
                "ref-redrive", "pi-redrive", Money.fromMinorUnits(approvedMinorUnits, "CNY"), "SETTLED")));
    }

    static PostSalesCase executingRefund() {
        PostSalesCase source = PostSalesCase.open("ord-redrive", PostSalesCaseType.REFUND,
            PostSalesScope.ticket("sb-redrive", "seg-redrive", "tvl-redrive", "ent-redrive"),
            "CUSTOMER_REQUEST", "acct-redrive", "idem-redrive", NOW, "cmd-open", "corr-open");
        source.beginEvaluation(NOW, "cmd-evaluate", "cmd-evaluate", "corr-evaluate");
        Money fare = Money.of("100.00", "CNY");
        RefundAssessment assessment = new RefundPolicyEngine().evaluateRefund(
            fare, NOW, NOW.plusSeconds(10 * 86_400), false, "ADULT", 1);
        source.recordDecision(PostSalesDecision.refund(source.caseId(), true, "ELIGIBLE",
            new RuleEvaluationSnapshot("quote-redrive", "rules-redrive", "1", NOW,
                Map.of("orderId", source.journeyOrderId())),
            AmountDecisionSnapshot.refund(assessment.penaltyAmount(), assessment.refundableAmount(),
                assessment.explanation(), assessment.componentDecisions()),
            assessment, NOW, NOW.plusSeconds(600)), false, false, NOW,
            "cmd-quote", "cmd-evaluate", "corr-evaluate");
        source.approve("http-approval", NOW.plusSeconds(1), "cmd-approve", "cmd-approve", "corr-approve");
        source.startExecution(NOW.plusSeconds(2), "cmd-execute", "cmd-approve", "corr-approve");
        return source;
    }
}
