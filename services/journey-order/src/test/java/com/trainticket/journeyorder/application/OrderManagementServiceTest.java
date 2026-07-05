package com.trainticket.journeyorder.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.journeyorder.adapters.InMemoryEventPublisher;
import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderRequest;
import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.journeyorder.application.port.in.JourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.OrderListResult;
import com.trainticket.journeyorder.application.service.OrderManagementService;
import com.trainticket.journeyorder.domain.EventEnvelope;
import com.trainticket.journeyorder.domain.DomainRuleViolation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderManagementServiceTest {

    private InMemoryEventPublisher eventPublisher;
    private OrderManagementService service;
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        eventPublisher = new InMemoryEventPublisher();
        service = new OrderManagementService(eventPublisher, FIXED_CLOCK);
    }

    @Test
    void createOrderPublishesJourneyOrderCreated() {
        var request = new JourneyOrderRequest("account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1"));

        JourneyOrderResult result = service.createOrder(request, "idem-1", "corr-1");

        assertEquals("account-1", result.accountId());
        assertEquals("offer-1", result.offerId());
        assertEquals("PENDING_CONFIRMATION", result.status());
        assertTrue(result.orderId() != null && !result.orderId().isBlank());

        assertEquals(1, eventPublisher.published().size());
        EventEnvelope envelope = eventPublisher.published().getFirst();
        assertEquals("JourneyOrderCreated", envelope.eventType());
        assertEquals("journey-order", envelope.producer());
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
        assertEquals(1, eventPublisher.published().size());
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

    private static void assertNotNull(Object obj) {
        if (obj == null) throw new AssertionError("Expected non-null");
    }
}
