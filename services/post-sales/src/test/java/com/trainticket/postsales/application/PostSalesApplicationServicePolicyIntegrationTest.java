package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.trainticket.postsales.domain.Money;
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
    void refundQuoteUsesStoredThreeDayDepartureContext() {
        InMemoryPostSalesPolicyContextStore contextStore = new InMemoryPostSalesPolicyContextStore();
        PostSalesApplicationService service = service(contextStore, quote("adjq-refund", 10_000, "CNY", 0, "CNY"));
        service.recordPolicyContext(PostSalesPolicyContext.fallback("ord-policy-1", NOW.plusSeconds(3 * 86_400), 1, Money.of("100.00", "CNY")));
        PostSalesCase postSalesCase = service.open(command("ord-policy-1", PostSalesCaseType.REFUND, "idem-refund-policy"));

        PostSalesCase evaluated = service.evaluate("psc-" + postSalesCase.caseId(), "cmd-evaluate", "corr-policy");

        assertEquals("TIER_2_TO_7_DAYS", evaluated.decision().refundAssessment().tierApplied());
        assertEquals(2_000, evaluated.decision().refundAssessment().penaltyAmount().toMinorUnits());
    }

    @Test
    void refundQuoteUsesInvoluntaryClassificationWithStoredDepartureContext() {
        InMemoryPostSalesPolicyContextStore contextStore = new InMemoryPostSalesPolicyContextStore();
        PostSalesApplicationService service = service(contextStore, quote("adjq-carrier", 10_000, "CNY", 0, "CNY"));
        service.recordPolicyContext(PostSalesPolicyContext.fallback("ord-policy-2", NOW.plusSeconds(3 * 86_400), 1, Money.of("100.00", "CNY")));
        PostSalesCase postSalesCase = service.open(new PostSalesApplicationService.OpenCaseCommand(
            "ord-policy-2",
            PostSalesCaseType.REFUND,
            scope(),
            "CARRIER_CANCELLED",
            "acct-1",
            "idem-carrier-policy",
            "cmd-open",
            "corr-policy"
        ));

        PostSalesCase evaluated = service.evaluate("psc-" + postSalesCase.caseId(), "cmd-evaluate", "corr-policy");

        assertEquals("INVOLUNTARY_OVERRIDE", evaluated.decision().refundAssessment().tierApplied());
        assertEquals(0, evaluated.decision().refundAssessment().penaltyAmount().toMinorUnits());
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
        AdjustmentQuotePort quotePort = ignored -> Optional.of(quote);
        return new PostSalesApplicationService(
            new InMemoryPostSalesRepository(),
            ignored -> { },
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
