package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.postsales.domain.AmountDecisionSnapshot;
import com.trainticket.postsales.domain.Money;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesDecision;
import com.trainticket.postsales.domain.PostSalesScope;
import com.trainticket.postsales.domain.RefundPolicyEngine;
import com.trainticket.postsales.domain.RuleEvaluationSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PostSalesExecutionRedriveServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");

    @Test
    void historicalExecutingCaseRebuildsStableApprovalAndExecutionTriggers() {
        InMemoryPostSalesRepository repository = new InMemoryPostSalesRepository();
        List<EventEnvelope> published = new ArrayList<>();
        PostSalesApplicationService service = service(repository, published);
        PostSalesCase source = executingRefund();
        repository.save(source);

        PostSalesCase first = service.approve(source.caseId(), "cmd-redrive-one", "corr-redrive-one");
        List<EventEnvelope> firstPair = List.copyOf(published);
        PostSalesCase second = service.approve(source.caseId(), "cmd-redrive-two", "corr-redrive-two");

        assertEquals(PostSalesCaseStatus.EXECUTING, first.status());
        assertEquals(PostSalesCaseStatus.EXECUTING, second.status());
        assertEquals(List.of("PostSalesApproved", "PostSalesExecutionStarted"),
            firstPair.stream().map(EventEnvelope::eventType).toList());
        assertEquals(firstPair, published.subList(2, 4));
        assertTrue(firstPair.stream().allMatch(event -> event.traceparent() == null && event.tracestate() == null));
        Map<?, ?> approvedActions = (Map<?, ?>) ((Map<?, ?>) firstPair.getFirst().payload()).get("approvedActions");
        Map<?, ?> refund = (Map<?, ?>) approvedActions.get("refund");
        Map<?, ?> amount = (Map<?, ?>) refund.get("amount");
        assertEquals(source.decision().amountSnapshot().refundAmount().toMinorUnits(),
            ((Number) amount.get("minorUnits")).longValue());
    }

    @Test
    void appliedCaseDoesNotPublishRedriveTriggers() {
        InMemoryPostSalesRepository repository = new InMemoryPostSalesRepository();
        List<EventEnvelope> published = new ArrayList<>();
        PostSalesApplicationService service = service(repository, published);
        PostSalesCase source = executingRefund();
        source.applyResult("already applied", NOW.plusSeconds(3), "cmd-apply", "cmd-apply", "corr-apply");
        repository.save(source);

        PostSalesCase result = service.approve(source.caseId(), "cmd-redrive", "corr-redrive");

        assertEquals(PostSalesCaseStatus.APPLIED, result.status());
        assertTrue(published.isEmpty());
    }

    private static PostSalesApplicationService service(
        InMemoryPostSalesRepository repository,
        List<EventEnvelope> published
    ) {
        return new PostSalesApplicationService(repository, published::add, ignored -> Optional.empty(),
            new InMemoryPostSalesPolicyContextStore(), Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
    }

    static PostSalesCase executingRefund() {
        PostSalesCase source = PostSalesCase.open("ord-redrive", PostSalesCaseType.REFUND,
            PostSalesScope.ticket("sb-redrive", "seg-redrive", "tvl-redrive", "ent-redrive"),
            "CUSTOMER_REQUEST", "acct-redrive", "idem-redrive", NOW, "cmd-open", "corr-open");
        source.beginEvaluation(NOW, "cmd-evaluate", "cmd-evaluate", "corr-evaluate");
        Money fare = Money.of("100.00", "CNY");
        var assessment = new RefundPolicyEngine().evaluateRefund(
            fare, NOW, NOW.plusSeconds(10 * 86_400), false, "ADULT", 1);
        source.recordDecision(PostSalesDecision.refund(source.caseId(), true, "ELIGIBLE",
            new RuleEvaluationSnapshot("quote-redrive", "rules-redrive", "1", NOW,
                Map.of("orderId", source.journeyOrderId())),
            AmountDecisionSnapshot.refund(assessment.penaltyAmount(), assessment.refundableAmount(),
                assessment.explanation(), assessment.componentDecisions()),
            assessment, NOW, NOW.plusSeconds(600)), false, false, NOW,
            "cmd-quote", "cmd-evaluate", "corr-evaluate");
        source.assignRefundPayments(List.of(Map.of(
            "paymentIntentId", "pi-redrive",
            "amount", PostSalesMapper.money(source.decision().amountSnapshot().refundAmount()))));
        source.approve("http-approval", NOW.plusSeconds(1), "cmd-approve", "cmd-approve", "corr-approve");
        source.startExecution(NOW.plusSeconds(2), "cmd-execute", "cmd-approve", "corr-approve");
        return source;
    }
}
