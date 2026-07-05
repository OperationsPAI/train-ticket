package com.trainticket.journeyorder.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.journeyorder.adapters.InMemoryEventPublisher;
import com.trainticket.journeyorder.api.dto.CancelJourneyOrderRequest;
import com.trainticket.journeyorder.api.dto.CancelJourneyOrderResponse;
import com.trainticket.journeyorder.api.dto.CreateJourneyOrderRequest;
import com.trainticket.journeyorder.api.dto.CreateJourneyOrderResponse;
import com.trainticket.journeyorder.api.dto.ErrorBody;
import com.trainticket.journeyorder.api.dto.ListJourneyOrdersResponse;
import com.trainticket.journeyorder.application.service.OrderManagementService;
import com.trainticket.journeyorder.domain.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class JourneyOrderControllerTest {

    private InMemoryEventPublisher eventPublisher;
    private OrderManagementService orderService;
    private JourneyOrderController controller;

    @BeforeEach
    void setUp() {
        eventPublisher = new InMemoryEventPublisher();
        Clock fixedClock = Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC);
        orderService = new OrderManagementService(eventPublisher, fixedClock);
        controller = new JourneyOrderController(orderService, null);
    }

    @Test
    void createOrderHappyPath() {
        var request = new CreateJourneyOrderRequest(
            "account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1")
        );

        ResponseEntity<?> response = controller.createOrder(request, "idem-1");

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertInstanceOf(CreateJourneyOrderResponse.class, response.getBody());

        CreateJourneyOrderResponse body = (CreateJourneyOrderResponse) response.getBody();
        assertEquals("account-1", body.accountId());
        assertEquals("offer-1", body.offerId());
        assertEquals("CREATED", body.status());
        assertNotNull(body.orderId());
        assertTrue(body.orderId().startsWith("ord-"));

        // Verify event was published
        assertEquals(1, eventPublisher.published().size());
        EventEnvelope envelope = eventPublisher.published().getFirst();
        assertEquals("JourneyOrderCreated", envelope.eventType());
        assertTrue(envelope.eventId().startsWith("evt-"));
    }

    @Test
    void createOrderIdempotentReplayReturnsOriginal() {
        var request = new CreateJourneyOrderRequest(
            "account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1")
        );

        ResponseEntity<?> response1 = controller.createOrder(request, "idem-2");
        ResponseEntity<?> response2 = controller.createOrder(request, "idem-2");

        assertEquals(HttpStatus.CREATED, response1.getStatusCode());
        assertEquals(HttpStatus.CREATED, response2.getStatusCode());

        CreateJourneyOrderResponse body1 = (CreateJourneyOrderResponse) response1.getBody();
        CreateJourneyOrderResponse body2 = (CreateJourneyOrderResponse) response2.getBody();
        assertEquals(body1.orderId(), body2.orderId());
        assertEquals(body1.monetarySummary(), body2.monetarySummary());
        assertEquals(10000L, body1.monetarySummary().subtotal().minorUnits());
        assertEquals("CNY", body1.monetarySummary().subtotal().currency());
    }

    @Test
    void getOrderReturnsNotFoundThroughCanonicalHandler() {
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
            OrderManagementService.NotFoundException.class,
            () -> controller.getOrder("nonexistent")
        );
        assertTrue(ex.getMessage().contains("Order not found"));
    }

    @Test
    void createOrderRejectsReusedIdempotencyKeyForDifferentBody() {
        var request = new CreateJourneyOrderRequest(
            "account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1")
        );
        var differentRequest = new CreateJourneyOrderRequest(
            "account-1", "offer-2", 1,
            List.of("tvl-1"), List.of("seg-1")
        );

        controller.createOrder(request, "idem-reused");

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
            OrderManagementService.IdempotencyKeyReused.class,
            () -> controller.createOrder(differentRequest, "idem-reused")
        );
        assertTrue(ex.getMessage().contains("Idempotency-Key"));
    }

    @Test
    void getOrderReturnsCreatedOrder() {
        var request = new CreateJourneyOrderRequest(
            "account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1")
        );
        ResponseEntity<?> createResponse = controller.createOrder(request, "idem-3");
        CreateJourneyOrderResponse createBody = (CreateJourneyOrderResponse) createResponse.getBody();
        String orderId = createBody.orderId();

        ResponseEntity<?> getResponse = controller.getOrder(orderId);
        assertEquals(HttpStatus.OK, getResponse.getStatusCode());
    }

    @Test
    void listOrdersSupportsPagination() {
        var request1 = new CreateJourneyOrderRequest(
            "account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1")
        );
        var request2 = new CreateJourneyOrderRequest(
            "account-1", "offer-2", 1,
            List.of("tvl-2"), List.of("seg-2")
        );
        controller.createOrder(request1, "idem-list-1");
        controller.createOrder(request2, "idem-list-2");

        ResponseEntity<ListJourneyOrdersResponse> listResponse = controller.listOrders("account-1", null, 1, 0);
        ListJourneyOrdersResponse body = listResponse.getBody();
        assertEquals(2, body.total());
        assertEquals(1, body.items().size());
    }

    @Test
    void cancelOrderReturnsCancelledStatus() {
        var createReq = new CreateJourneyOrderRequest(
            "account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1")
        );
        ResponseEntity<?> createResponse = controller.createOrder(createReq, "idem-cancel-1");
        CreateJourneyOrderResponse createBody = (CreateJourneyOrderResponse) createResponse.getBody();
        eventPublisher.clear();
        String orderId = createBody.orderId();

        var cancelReq = new CancelJourneyOrderRequest("change of plans");
        ResponseEntity<?> cancelResponse = controller.cancelOrder(orderId, cancelReq, "idem-cancel-2");
        assertEquals(HttpStatus.OK, cancelResponse.getStatusCode());
        CancelJourneyOrderResponse cancelBody = (CancelJourneyOrderResponse) cancelResponse.getBody();
        assertEquals(orderId, cancelBody.orderId());
        assertEquals("CANCELLED", cancelBody.status());
        assertNotNull(cancelBody.cancelledAt());
        assertEquals(1, eventPublisher.published().size());
    }

    @Test
    void publisherWrapsEventInCorrectEnvelope() {
        var request = new CreateJourneyOrderRequest(
            "account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1")
        );

        controller.createOrder(request, "idem-env-1");

        assertEquals(1, eventPublisher.published().size());
        EventEnvelope envelope = eventPublisher.published().getFirst();

        // Verify envelope fields per shared-primitives.md
        assertEquals("JourneyOrderCreated", envelope.eventType());
        assertEquals(1, envelope.schemaVersion());
        assertEquals("journey-order", envelope.producer());
        assertTrue(envelope.eventId().startsWith("evt-"));
        assertNotNull(envelope.occurredAt());
        assertNotNull(envelope.correlationId());
        assertNotNull(envelope.causationId());
        assertTrue(envelope.payload().containsKey("orderId"));
        assertTrue(String.valueOf(envelope.payload().get("orderId")).startsWith("ord-"));
        assertTrue(envelope.payload().containsKey("monetarySummary"));
    }
}
