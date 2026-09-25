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
