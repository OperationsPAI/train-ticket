package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.postsales.domain.Money;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PostSalesApplicationServicePolicyIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-03T10:00:00Z");

    @Test
    void refundQuoteUsesFarePricingRefundableAmount() {
        InMemoryPostSalesPolicyContextStore contextStore = new InMemoryPostSalesPolicyContextStore();
        PostSalesApplicationService service = service(contextStore, quote("adjq-refund", 8_750, "CNY", 0, "CNY"));
        service.recordPolicyContext(PostSalesPolicyContext.fallback("ord-policy-1", NOW.plusSeconds(3 * 86_400), 1, Money.of("107.50", "CNY")));
        PostSalesCase postSalesCase = service.open(command("ord-policy-1", PostSalesCaseType.REFUND, "idem-refund-policy"));

        PostSalesCase evaluated = service.evaluate("psc-" + postSalesCase.caseId(), "cmd-evaluate", "corr-policy");

        assertEquals("FARE_RULE_QUOTE", evaluated.decision().refundAssessment().tierApplied());
        assertEquals(8_750, evaluated.decision().amountSnapshot().refundAmount().toMinorUnits());
        assertEquals(2_000, evaluated.decision().refundAssessment().penaltyAmount().toMinorUnits());
    }

    @Test
    void zeroQuoteFallsBackToStoredPolicyContext() {
        InMemoryPostSalesPolicyContextStore contextStore = new InMemoryPostSalesPolicyContextStore();
        PostSalesApplicationService service = service(contextStore, quote("adjq-zero", 0, "CNY", 0, "CNY"));
        service.recordPolicyContext(PostSalesPolicyContext.fallback("ord-policy-2", NOW.plusSeconds(3 * 86_400), 1, Money.of("100.00", "CNY")));
        PostSalesCase postSalesCase = service.open(command("ord-policy-2", PostSalesCaseType.REFUND, "idem-zero-policy"));

        PostSalesCase evaluated = service.evaluate("psc-" + postSalesCase.caseId(), "cmd-evaluate", "corr-policy");

        assertEquals("TIER_2_TO_7_DAYS", evaluated.decision().refundAssessment().tierApplied());
        assertEquals(8_000, evaluated.decision().amountSnapshot().refundAmount().toMinorUnits());
        assertEquals(2_000, evaluated.decision().refundAssessment().penaltyAmount().toMinorUnits());
    }

    @Test
    void approveStartsExecutionAndCapacityReleaseAppliesCase() {
        InMemoryPostSalesPolicyContextStore contextStore = new InMemoryPostSalesPolicyContextStore();
        InMemoryPostSalesRepository repository = new InMemoryPostSalesRepository();
        List<EventEnvelope> events = new java.util.ArrayList<>();
        PostSalesApplicationService service = service(repository, contextStore, quote("adjq-apply", 8_750, "CNY", 0, "CNY"), events);
        service.recordPolicyContext(PostSalesPolicyContext.fallback("ord-apply", NOW.plusSeconds(3 * 86_400), 1, Money.of("107.50", "CNY")));
        PostSalesCase postSalesCase = service.open(command("ord-apply", PostSalesCaseType.REFUND, "idem-apply"));
        service.evaluate("psc-" + postSalesCase.caseId(), "cmd-evaluate", "corr-policy");

        PostSalesCase approved = service.approve("psc-" + postSalesCase.caseId(), "cmd-approve", "corr-policy");

        assertEquals(PostSalesCaseStatus.EXECUTING, approved.status());

        service.applyForSegmentBooking("oi-1", "evt-capacity", "corr-policy");
        PostSalesCase applied = service.get("psc-" + postSalesCase.caseId());

        assertEquals(PostSalesCaseStatus.APPLIED, applied.status());
        assertTrue(events.stream().anyMatch(event -> "PostSalesExecutionStarted".equals(event.eventType())));
        EventEnvelope appliedEvent = events.stream()
            .filter(event -> "PostSalesApplied".equals(event.eventType()))
            .reduce((first, second) -> second)
            .orElseThrow();
        assertTrue(((Map<?, ?>) appliedEvent.payload()).containsKey("scope"));
        Map<?, ?> appliedRefund = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) appliedEvent.payload()).get("resultSummary")).get("refundableAmount");
        assertEquals("CNY", appliedRefund.get("currency"));
        assertEquals(8_750L, ((Number) appliedRefund.get("minorUnits")).longValue());
    }

    @Test
    void secondChangeUsesPersistedChangeCountAndActualDepartureContext() {
        InMemoryPostSalesPolicyContextStore contextStore = new InMemoryPostSalesPolicyContextStore();
        contextStore.save(new PostSalesPolicyContext(
            "ord-change-policy",
            NOW.plusSeconds(20 * 86_400),
            Map.of("tvl-1", "ADULT"),
            1,
            1,
            Money.of("100.00", "CNY"),
            PostSalesPolicyContext.RefundWaterfallComponents.empty(java.util.Currency.getInstance("CNY"))
        ));
        PostSalesApplicationService service = service(contextStore, quote("adjq-change", 0, "CNY", 5_000, "CNY"));
        PostSalesCase postSalesCase = service.open(command("ord-change-policy", PostSalesCaseType.CHANGE, "idem-change-policy"));

        PostSalesCase evaluated = service.evaluate("psc-" + postSalesCase.caseId(), "cmd-evaluate", "corr-policy");

        assertEquals(2_000, evaluated.decision().changeAssessment().changeFee().toMinorUnits());
        assertEquals(7_000, evaluated.decision().changeAssessment().netPayable().toMinorUnits());
    }

    private static PostSalesApplicationService service(
        PostSalesPolicyContextStore contextStore,
        AdjustmentQuotePort.AdjustmentQuoteResult quote
    ) {
        return service(new InMemoryPostSalesRepository(), contextStore, quote, new java.util.ArrayList<>());
    }

    private static PostSalesApplicationService service(
        InMemoryPostSalesRepository repository,
        PostSalesPolicyContextStore contextStore,
        AdjustmentQuotePort.AdjustmentQuoteResult quote,
        List<EventEnvelope> events
    ) {
        AdjustmentQuotePort quotePort = ignored -> Optional.of(quote);
        return new PostSalesApplicationService(
            repository,
            events::add,
            quotePort,
            contextStore,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static AdjustmentQuotePort.AdjustmentQuoteResult quote(
        String quoteId,
        long refundableMinorUnits,
        String refundableCurrency,
        long amountDueMinorUnits,
        String amountDueCurrency
    ) {
        return new AdjustmentQuotePort.AdjustmentQuoteResult(
            quoteId,
            "QUOTED",
            refundableMinorUnits,
            refundableCurrency,
            amountDueMinorUnits,
            amountDueCurrency
        );
    }

    private static PostSalesApplicationService.OpenCaseCommand command(String orderId, PostSalesCaseType caseType, String idempotencyKey) {
        return new PostSalesApplicationService.OpenCaseCommand(
            orderId,
            caseType,
            scope(),
            "CUSTOMER_REQUEST",
            "acct-1",
            idempotencyKey,
            "cmd-open",
            "corr-policy"
        );
    }

    private static PostSalesScope scope() {
        return new PostSalesScope(List.of("oi-1"), List.of("seg-1"), List.of("tvl-1"), List.of("ent-1"));
    }
}
