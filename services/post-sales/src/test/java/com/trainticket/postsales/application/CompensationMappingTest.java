package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.trainticket.postsales.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompensationMappingTest {
    @Test
    void compensationExecutionUsesTheSupportedRefundVoidReason() {
        Instant now = Instant.now();
        PostSalesCase source = PostSalesCase.open("ord-mapping", PostSalesCaseType.COMPENSATION,
            PostSalesScope.ticket("sb-mapping", "seg-mapping", "tvl-mapping", "ent-mapping"),
            "SERVICE_QUALITY_COMPLAINT", "acc-mapping", "mapping-key", now, "cmd-mapping", "corr-mapping");
        source.beginEvaluation(now, "cmd-evaluate", "cmd-mapping", "corr-mapping");
        source.recordDecision(new PostSalesDecision(source.caseId(), 1, DecisionKind.COMPENSATION, true, "ELIGIBLE",
            new RuleEvaluationSnapshot("quote-mapping", "rule-mapping", "v1", now, Map.of("journeyOrderId", source.journeyOrderId())),
            AmountDecisionSnapshot.refund(Money.of("0", "CNY"), Money.of("10", "CNY"), "approved compensation"),
            null, null, null, now, now.plusSeconds(900)), false, false, now, "cmd-quote", "cmd-evaluate", "corr-mapping");
        source.approve("approval-mapping", now, "cmd-approve", "cmd-quote", "corr-mapping");
        PostSalesApproved approved = (PostSalesApproved) source.domainEvents().stream()
            .filter(PostSalesApproved.class::isInstance).findFirst().orElseThrow();
        Map<?, ?> payload = (Map<?, ?>) PostSalesMapper.toEnvelope(approved, source).payload();
        Map<?, ?> actions = (Map<?, ?>) payload.get("approvedActions");
        assertEquals("COMPENSATION", actions.get("decisionKind"));
        List<?> steps = (List<?>) actions.get("steps");
        assertEquals("REFUND", ((Map<?, ?>) steps.getFirst()).get("reason"));
    }
}
