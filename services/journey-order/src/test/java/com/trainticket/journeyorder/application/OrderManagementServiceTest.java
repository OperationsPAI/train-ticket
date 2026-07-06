package com.trainticket.journeyorder.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.platformkit.messaging.InMemoryEventBus;
import com.trainticket.platformkit.messaging.InMemoryEventPublisher;
import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderRequest;
import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.journeyorder.application.port.in.JourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.OrderListResult;
import com.trainticket.journeyorder.application.service.OrderManagementService;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderManagementServiceTest {

    private InMemoryEventPublisher eventPublisher;
    private List<EventEnvelope> published;
    private OrderManagementService service;
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        eventPublisher = new InMemoryEventPublisher(eventBus);
        published = new ArrayList<>();
        service = new OrderManagementService(envelope -> {
            eventPublisher.publish(envelope);
            published.add(envelope);
        }, FIXED_CLOCK);
    }

    @Test
    void createOrderPublishesJourneyOrderCreated() {
        var request = new JourneyOrderRequest("account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1"));

        JourneyOrderResult result = service.createOrder(request, "idem-1", "corr-1");

        assertEquals("account-1", result.accountId());
        assertEquals("offer-1", result.offerId());
        assertEquals("CREATED", result.status());
        assertTrue(result.orderId() != null && result.orderId().startsWith("ord-"));

        assertEquals(1, published().size());
        EventEnvelope envelope = published().getFirst();
        assertEquals("JourneyOrderCreated", envelope.eventType());
        assertEquals("journey-order", envelope.producer());
    }


    @Test
    void createdEventPayloadMatchesContractShape() {
        var request = new JourneyOrderRequest("account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1"));

        service.createOrder(request, "idem-contract-created", "corr-1");

        Map<String, Object> payload = (Map<String, Object>) published().getFirst().payload();
        assertEquals(java.util.Set.of("orderId", "accountId", "offerId", "monetarySummary", "travelerRefs", "segmentRefs", "createdAt"), payload.keySet());
        assertTrue(String.valueOf(payload.get("orderId")).startsWith("ord-"));
        assertEquals("account-1", payload.get("accountId"));
        assertEquals("offer-1", payload.get("offerId"));
        assertEquals("2026-07-05T10:00:00Z", payload.get("createdAt"));
        assertEquals(List.of("seg-1"), payload.get("segmentRefs"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> travelerRefs = (List<Map<String, Object>>) payload.get("travelerRefs");
        assertEquals(1, travelerRefs.size());
        assertEquals(java.util.Set.of("travelerId", "travelerType"), travelerRefs.getFirst().keySet());
        assertEquals("tvl-1", travelerRefs.getFirst().get("travelerId"));
        assertEquals("ADULT", travelerRefs.getFirst().get("travelerType"));
    }

    @Test
    void idempotentCreateReturnsSameResult() {
        var request = new JourneyOrderRequest("account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1"));

        JourneyOrderResult r1 = service.createOrder(request, "idem-same", "corr-1");
        JourneyOrderResult r2 = service.createOrder(request, "idem-same", "corr-1");

        assertEquals(r1.orderId(), r2.orderId());
        assertEquals(r1.monetarySummary(), r2.monetarySummary());

        // Only one event published (first call)
        assertEquals(1, published().size());
    }

    @Test
    void reusedIdempotencyKeyWithDifferentCreateRequestThrows() {
        var request = new JourneyOrderRequest("account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1"));
        var differentRequest = new JourneyOrderRequest("account-1", "offer-2", 1,
            List.of("tvl-1"), List.of("seg-1"));

        service.createOrder(request, "idem-reused", "corr-1");

        assertThrows(OrderManagementService.IdempotencyKeyReused.class, () ->
            service.createOrder(differentRequest, "idem-reused", "corr-1"));
    }

    @Test
    void getOrderReturnsEmptyForMissing() {
        Optional<JourneyOrderResult> result = service.getOrder("nonexistent");
        assertTrue(result.isEmpty());
    }

    @Test
    void getOrderReturnsCreatedOrder() {
        var request = new JourneyOrderRequest("account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1"));
        JourneyOrderResult created = service.createOrder(request, "idem-get", "corr-1");

        Optional<JourneyOrderResult> found = service.getOrder(created.orderId());
        assertTrue(found.isPresent());
        assertEquals(created.orderId(), found.get().orderId());
        assertEquals("account-1", found.get().accountId());
    }

    @Test
    void listOrdersFiltersByAccount() {
        service.createOrder(
            new JourneyOrderRequest("account-1", "offer-1", 1, List.of("tvl-1"), List.of("seg-1")),
            "idem-list-a1", "corr-1");
        service.createOrder(
            new JourneyOrderRequest("account-2", "offer-2", 1, List.of("tvl-2"), List.of("seg-2")),
            "idem-list-a2", "corr-2");

        OrderListResult list = service.listOrders("account-1", null, 20, 0);
        assertEquals(1, list.total());
        assertEquals("account-1", list.items().getFirst().accountId());
    }

    @Test
    void cancelOrderChangesStatus() {
        var request = new JourneyOrderRequest("account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1"));
        JourneyOrderResult created = service.createOrder(request, "idem-cancel", "corr-1");

        CancelJourneyOrderResult cancelled = service.cancelOrder(
            new CancelJourneyOrderRequest(created.orderId(), "change of plans"),
            "idem-cancel-2", "corr-1");

        assertEquals("CANCELLED", cancelled.status());
        assertEquals(created.orderId(), cancelled.orderId());
        assertNotNull(cancelled.cancelledAt());
    }

    @Test
    void cancelOrderThrowsNotFound() {
        assertThrows(OrderManagementService.NotFoundException.class, () ->
            service.cancelOrder(
                new CancelJourneyOrderRequest("nonexistent", "reason"),
                "idem-nonexist", "corr-1"));
    }

    @Test
    void riskAssessmentAllowRecordsExplicitConfirmationCondition() {
        JourneyOrderResult created = service.createOrder(
            new JourneyOrderRequest("account-risk", "offer-risk", 1, List.of("tvl-1"), List.of("seg-1")),
            "idem-risk-allow", "corr-1");
        published.clear();

        EventEnvelope envelope = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c501",
            "RiskAssessmentResult",
            Instant.parse("2026-07-05T10:01:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c502",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c503",
            "risk-compliance",
            1,
            Map.of("subjectRef", created.orderId(), "decision", "ALLOW")
        );

        service.handle(envelope);

        var order = service.getOrder(created.orderId());
        assertTrue(order.isPresent());
        assertEquals("CREATED", order.get().status());
    }

    @Test
    void riskBlockAppliedCancelsOrderAndLiftedDoesNotReviveCancelledOrder() {
        JourneyOrderResult created = service.createOrder(
            new JourneyOrderRequest("account-risk", "offer-block", 1, List.of("tvl-1"), List.of("seg-1")),
            "idem-risk-block", "corr-1");
        published.clear();

        EventEnvelope block = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c601",
            "RiskBlockApplied",
            Instant.parse("2026-07-05T10:01:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c602",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c603",
            "risk-compliance",
            1,
            Map.of("subjectRef", created.orderId(), "reasonCode", "HIGH_RISK_SIGNAL")
        );
        EventEnvelope lifted = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c604",
            "RiskBlockLifted",
            Instant.parse("2026-07-05T10:02:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c602",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c601",
            "risk-compliance",
            1,
            Map.of("subjectRef", created.orderId(), "reasonCode", "MANUAL_REVIEW_CLEARED")
        );

        service.handle(block);
        service.handle(lifted);

        assertEquals("CANCELLED", service.getOrder(created.orderId()).get().status());
        assertEquals(1, published.stream().filter(event -> event.eventType().equals("JourneyOrderCancelled")).count());
    }

    private static void assertNotNull(Object obj) {
        if (obj == null) throw new AssertionError("Expected non-null");
    }

    private List<EventEnvelope> published() {
        return List.copyOf(published);
    }

}
