package com.trainticket.journeyorder.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyOrderTest {
    private static final Instant NOW = Instant.parse("2026-07-03T12:00:00Z");

    @Test
    void createsJourneyOrderFromValidOfferWithTravelerSnapshotsAndCreatedEvent() {
        JourneyOrder order = sampleOrder();

        assertEquals(OrderLifecycleState.PENDING_CONFIRMATION, order.state());
        assertEquals("account-1:offer-1:client-req-1", order.idempotencyKey());
        assertEquals(Money.of("CNY", "143.00"), order.monetarySummary().payableTotal());
        assertEquals(1, order.travelers().size());
        assertEquals(1, order.segments().size());
        assertEquals(1, order.timeline().size());
        JourneyOrderEvent event = assertInstanceOf(JourneyOrderCreated.class, order.domainEvents().getFirst());
        assertEquals("JourneyOrderCreated", event.eventType());
        assertEquals(1, event.schemaVersion());
        assertEquals(order.orderId(), event.orderId());
    }

    @Test
    void refusesExpiredOfferAndInvalidTravelerBinding() {
        OfferSnapshotRef expiredOffer = new OfferSnapshotRef("offer-expired", 1, NOW.minusSeconds(600), NOW.minusSeconds(1), "rule-1");
        assertThrows(DomainRuleViolation.class, () -> createOrder(expiredOffer, sampleItems("missing-traveler")));

        assertThrows(DomainRuleViolation.class, () -> createOrder(sampleOffer(), sampleItems("unknown-traveler")));
    }

    @Test
    void monetarySummaryRequiresCurrencyAndPayableInvariants() {
        assertThrows(DomainRuleViolation.class, () -> new MonetarySummary(
            java.util.Currency.getInstance("CNY"),
            Money.of("CNY", "100.00"),
            Money.of("CNY", "10.00"),
            Money.of("CNY", "5.00"),
            Money.of("CNY", "-2.00"),
            Money.of("CNY", "0.00"),
            Money.of("CNY", "999.00")
        ));
        assertThrows(DomainRuleViolation.class, () -> List.of(
            new OrderItem("fare", OrderItemType.SEGMENT_FARE, "fare", Money.of("CNY", "100.00"), "segment-1", List.of(binding("fare", "traveler-1"))),
            new OrderItem("fee", OrderItemType.SERVICE_FEE, "fee", Money.of("USD", "1.00"), "fee-rule", List.of())
        ).stream().collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), MonetarySummary::fromItems)));
    }

    @Test
    void paymentCapturedCannotDirectlyConfirmWithoutAcceptedDomainFacts() {
        JourneyOrder order = sampleOrder();
        order.markBookingAndCapacityAccepted(NOW.plusSeconds(1), "cmd-booking", "corr-1");
        order.markPendingPayment("initial-ticket-purchase", NOW.plusSeconds(2), "cmd-pay-request", "corr-1");
        order.recordPaymentCaptured("payment-intent-1", NOW.plusSeconds(3), "cmd-payment", "payment-event-1", "corr-1");

        assertEquals(OrderLifecycleState.CONFIRMING, order.state());
        assertTrue(order.confirmationConditions().paymentConditionSatisfied());
        assertFalse(order.confirmationConditions().entitlementSummaryAccepted());
        assertThrows(DomainRuleViolation.class, () -> order.confirm("attempt-1", NOW.plusSeconds(4), "cmd-confirm", "payment-event-1", "corr-1"));
    }

    @Test
    void confirmsOnlyAfterBookingCapacityPaymentAndEntitlementSummariesAccepted() {
        JourneyOrder order = sampleOrder();
        order.markBookingAndCapacityAccepted(NOW.plusSeconds(1), "cmd-booking", "corr-1");
        order.markPendingPayment("initial-ticket-purchase", NOW.plusSeconds(2), "cmd-pay-request", "corr-1");
        order.recordPaymentCaptured("payment-intent-1", NOW.plusSeconds(3), "cmd-payment", "payment-event-1", "corr-1");
        order.recordEntitlementSummaryAccepted(NOW.plusSeconds(4), "cmd-entitlement", "entitlement-event-1", "corr-1");
        order.confirm("attempt-1", NOW.plusSeconds(5), "cmd-confirm", "entitlement-event-1", "corr-1");

        assertEquals(OrderLifecycleState.CONFIRMED, order.state());
        assertInstanceOf(JourneyOrderConfirmed.class, order.domainEvents().getLast());
        assertEquals(List.of(
            "JourneyOrderCreated",
            "BookingCapacitySummaryAccepted",
            "JourneyOrderPendingPayment",
            "PaymentCaptured",
            "EntitlementSummaryAccepted",
            "JourneyOrderConfirmed"
        ), order.timeline().stream().map(TimelineFact::factType).toList());
    }

    @Test
    void paymentExpiryCancelsPendingPaymentOrder() {
        JourneyOrder order = sampleOrder();
        order.markBookingAndCapacityAccepted(NOW.plusSeconds(1), "cmd-booking", "corr-1");
        order.markPendingPayment("initial-ticket-purchase", NOW.plusSeconds(2), "cmd-pay-request", "corr-1");

        order.expirePayment("payment-intent-1", NOW.plusSeconds(3), "cmd-expire", "payment-expired-1", "corr-1");

        assertEquals(OrderLifecycleState.CANCELLED, order.state());
        assertInstanceOf(JourneyOrderCancelled.class, order.domainEvents().getLast());
    }

    @Test
    void postSalesAdjustmentCancelsItemAndRecomputesCommercialSummaryWithoutOwningRefund() {
        JourneyOrder order = confirmedOrder();

        order.applyPostSalesItemCancellation("meal", "post-sales-case-1", "ancillary cancelled by post sales", NOW.plusSeconds(10), "cmd-adjust", "post-sales-applied-1", "corr-1");

        assertEquals(OrderLifecycleState.POST_SALES_ADJUSTED, order.state());
        assertEquals(Money.of("CNY", "128.00"), order.monetarySummary().payableTotal());
        assertEquals(Money.of("CNY", "15.00"), order.monetarySummary().cancelledTotal());
        assertInstanceOf(JourneyOrderPostSalesAdjusted.class, order.domainEvents().getLast());
        assertTrue(order.timeline().stream().anyMatch(fact -> fact.factType().equals("PostSalesAdjustmentApplied")
            && fact.attributes().get("postSalesCaseId").equals("post-sales-case-1")));
    }

    private static JourneyOrder confirmedOrder() {
        JourneyOrder order = sampleOrder();
        order.markBookingAndCapacityAccepted(NOW.plusSeconds(1), "cmd-booking", "corr-1");
        order.markPendingPayment("initial-ticket-purchase", NOW.plusSeconds(2), "cmd-pay-request", "corr-1");
        order.recordPaymentCaptured("payment-intent-1", NOW.plusSeconds(3), "cmd-payment", "payment-event-1", "corr-1");
        order.recordEntitlementSummaryAccepted(NOW.plusSeconds(4), "cmd-entitlement", "entitlement-event-1", "corr-1");
        order.confirm("attempt-1", NOW.plusSeconds(5), "cmd-confirm", "entitlement-event-1", "corr-1");
        return order;
    }

    private static JourneyOrder sampleOrder() {
        return createOrder(sampleOffer(), sampleItems("traveler-1"));
    }

    private static JourneyOrder createOrder(OfferSnapshotRef offer, List<OrderItem> items) {
        return JourneyOrder.createFromOffer(
            "account-1",
            "mobile-app",
            "client-req-1",
            offer,
            List.of(new TravelerRef("traveler-1", "doc-mask-1", "ADULT", "qualification-v1")),
            List.of(new SegmentOrderSnapshot(
                "segment-1",
                "BJP",
                "SHA",
                "TRAIN",
                NOW.plusSeconds(86_400),
                NOW.plusSeconds(111_600),
                "connection-contract-1"
            )),
            items,
            NOW,
            "cmd-create",
            "corr-1"
        );
    }

    private static OfferSnapshotRef sampleOffer() {
        return new OfferSnapshotRef("offer-1", 3, NOW.minusSeconds(60), NOW.plusSeconds(600), "rule-snapshot-1");
    }

    private static List<OrderItem> sampleItems(String travelerId) {
        return List.of(
            new OrderItem("fare", OrderItemType.SEGMENT_FARE, "train fare", Money.of("CNY", "120.00"), "segment-1", List.of(binding("fare", travelerId))),
            new OrderItem("meal", OrderItemType.ANCILLARY_SERVICE, "meal ancillary", Money.of("CNY", "15.00"), "ancillary-meal-1", List.of(new OrderLineBinding("meal", travelerId, "segment-1", "ancillary-meal-1"))),
            new OrderItem("service-fee", OrderItemType.SERVICE_FEE, "service fee", Money.of("CNY", "10.00"), "fee-rule-1", List.of()),
            new OrderItem("discount", OrderItemType.DISCOUNT, "coupon", Money.of("CNY", "-2.00"), "promotion-ref-1", List.of())
        );
    }

    private static OrderLineBinding binding(String itemId, String travelerId) {
        return new OrderLineBinding(itemId, travelerId, "segment-1", "");
    }
}
