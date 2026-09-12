package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.postsales.domain.Money;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
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
    void refreshingTheFareKeepsTheStoredDepartureTime() {
        // JourneyOrderConfirmed and JourneyOrderPostSalesAdjusted carry
        // [orderId, accountId, monetarySummary] and NO segments. They used to be
        // handed to the full context mapper, which returned empty for want of a
        // departure time and logged a warning on every order claiming refunds
        // would quote zero -- while JourneyOrderCreated had already stored a
        // perfectly usable context. What they must do instead is update the fare
        // and leave the departure time intact.
        InMemoryPostSalesPolicyContextStore contextStore = new InMemoryPostSalesPolicyContextStore();
        PostSalesApplicationService service = service(contextStore, quote("adjq-refresh", 10_000, "CNY", 0, "CNY"));
        Instant departure = NOW.plusSeconds(3 * 86_400);
        service.recordPolicyContext(PostSalesPolicyContext.fallback("ord-refresh-1", departure, 1, Money.of("100.00", "CNY")));

        service.refreshPolicyContextFare("ord-refresh-1", java.util.Map.of(
            "orderId", "ord-refresh-1",
            "monetarySummary", java.util.Map.of(
                "total", java.util.Map.of("currency", "CNY", "minorUnits", 12_000))));

        PostSalesPolicyContext updated = contextStore.findByOrderId("ord-refresh-1").orElseThrow();
        assertEquals(departure, updated.departureTime(), "the departure time must survive a fare refresh");
        assertEquals(12_000, updated.originalFare().toMinorUnits(), "the fare must be updated");
    }

    @Test
    void refreshingTheFareOfAnUnknownOrderIsANoOp() {
        // No context yet means no departure time to attach a fare to. Doing
        // nothing is correct; the alternative is inventing one.
        InMemoryPostSalesPolicyContextStore contextStore = new InMemoryPostSalesPolicyContextStore();
        PostSalesApplicationService service = service(contextStore, quote("adjq-none", 10_000, "CNY", 0, "CNY"));

        service.refreshPolicyContextFare("ord-unknown", java.util.Map.of(
            "orderId", "ord-unknown",
            "monetarySummary", java.util.Map.of(
                "total", java.util.Map.of("currency", "CNY", "minorUnits", 9_000))));

        assertTrue(contextStore.findByOrderId("ord-unknown").isEmpty());
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
    void changeUsesFarePricingAmountDueWithoutReapplyingChangeFee() {
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

        assertEquals(1_500, evaluated.decision().changeAssessment().changeFee().toMinorUnits());
        assertEquals(5_000, evaluated.decision().changeAssessment().netPayable().toMinorUnits());
    }

    @Test
    void sameFareChangeQuoteReturnsContractedDefaultChangeFeeAsAmountDue() {
        InMemoryPostSalesPolicyContextStore contextStore = new InMemoryPostSalesPolicyContextStore();
        contextStore.save(new PostSalesPolicyContext(
            "ord-change-same-fare",
            NOW.plusSeconds(3 * 86_400),
            Map.of("tvl-1", "ADULT"),
            1,
            0,
            Money.of("75.00", "CNY"),
            PostSalesPolicyContext.RefundWaterfallComponents.empty(java.util.Currency.getInstance("CNY"))
        ));
        PostSalesApplicationService service = service(contextStore, quote("adjq-change-same-fare", 0, "CNY", 1_500, "CNY"));
        PostSalesCase postSalesCase = service.open(command("ord-change-same-fare", PostSalesCaseType.CHANGE, "idem-change-same-fare"));

        PostSalesCase evaluated = service.evaluate("psc-" + postSalesCase.caseId(), "cmd-evaluate", "corr-policy");

        assertEquals(1_500, evaluated.decision().amountSnapshot().extraChargeAmount().toMinorUnits());
        assertEquals(1_500, evaluated.decision().changeAssessment().changeFee().toMinorUnits());
        assertEquals(0, evaluated.decision().changeAssessment().fareDifference().toMinorUnits());
    }

    @Test
    void approveStartsExecutionSoTheCaseCanConvergeToApplied() {
        InMemoryPostSalesPolicyContextStore contextStore = new InMemoryPostSalesPolicyContextStore();
        PostSalesApplicationService service = service(contextStore, quote("adjq-apply", 8_750, "CNY", 0, "CNY"));
        service.recordPolicyContext(PostSalesPolicyContext.fallback("ord-apply", NOW.plusSeconds(3 * 86_400), 1, Money.of("107.50", "CNY")));
        PostSalesCase postSalesCase = service.open(command("ord-apply", PostSalesCaseType.REFUND, "idem-apply"));
        service.evaluate("psc-" + postSalesCase.caseId(), "cmd-evaluate", "corr-policy");

        PostSalesCase approved = service.approve("psc-" + postSalesCase.caseId(), "cmd-approve", "corr-policy");

        assertEquals(PostSalesCaseStatus.EXECUTING, approved.status());

        service.applyForSegmentBooking("oi-1", "evt-capacity", "corr-policy");

        assertEquals(PostSalesCaseStatus.APPLIED, service.get("psc-" + postSalesCase.caseId()).status());
    }

    @Test
    void approveIsIdempotentOnceTheCaseIsExecuting() {
        InMemoryPostSalesPolicyContextStore contextStore = new InMemoryPostSalesPolicyContextStore();
        PostSalesApplicationService service = service(contextStore, quote("adjq-idem", 8_750, "CNY", 0, "CNY"));
        service.recordPolicyContext(PostSalesPolicyContext.fallback("ord-idem", NOW.plusSeconds(3 * 86_400), 1, Money.of("107.50", "CNY")));
        PostSalesCase postSalesCase = service.open(command("ord-idem", PostSalesCaseType.REFUND, "idem-idem"));
        service.evaluate("psc-" + postSalesCase.caseId(), "cmd-evaluate", "corr-policy");
        service.approve("psc-" + postSalesCase.caseId(), "cmd-approve", "corr-policy");

        PostSalesCase again = service.approve("psc-" + postSalesCase.caseId(), "cmd-approve-2", "corr-policy");

        assertEquals(PostSalesCaseStatus.EXECUTING, again.status());
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
