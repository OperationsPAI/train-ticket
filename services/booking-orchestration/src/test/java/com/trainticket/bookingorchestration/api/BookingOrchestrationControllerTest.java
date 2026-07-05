package com.trainticket.bookingorchestration.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.bookingorchestration.RequestContextFilter;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class BookingOrchestrationControllerTest {

    private TestEventPublisher eventPublisher;
    private BookingOrchestrationService bookingService;
    private BookingOrchestrationController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC);
        eventPublisher = new TestEventPublisher();
        bookingService = new BookingOrchestrationService(clock, eventPublisher);
        controller = new BookingOrchestrationController(bookingService);
        request = new MockHttpServletRequest();
        request.setAttribute(RequestContextFilter.CORRELATION_ID_HEADER, "corr-" + UUID.randomUUID());
    }

    private String createTestSaga() {
        var req = new BookingOrchestrationController.StartSagaRequest(
            "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c123",
            "acc-123",
            "off-123",
            List.of("tvl-user1"),
            List.of("seg-001"));
        var resp = controller.startSaga(req, UUID.randomUUID().toString(), request);
        eventPublisher.clear();
        return ((BookingOrchestrationController.StartSagaResponse) resp.getBody()).sagaId();
    }

    private String createConfirmedSegment(String sagaId) {
        String segmentBookingId = "sb-" + UUID.randomUUID();
        controller.requestReservation(sagaId,
            new BookingOrchestrationController.RequestReservationRequest("seg-001", "tvl-user1", segmentBookingId),
            UUID.randomUUID().toString(), request);
        bookingService.handleUpstreamEvent(new com.trainticket.platformkit.messaging.EventEnvelope(
            "evt-" + UUID.randomUUID(), "ProviderReservationConfirmed", 1, "provider-integration",
            "evt-" + UUID.randomUUID(), (String) request.getAttribute(RequestContextFilter.CORRELATION_ID_HEADER),
            Instant.parse("2026-07-05T10:00:01Z"),
            Map.of("segmentBookingId", segmentBookingId,
                "providerReference", Map.of("providerId", "provider-1", "reservationId", "res-1", "displayReference", "PNR1"),
                "normalizedEvidence", "confirmed")));
        eventPublisher.clear();
        return segmentBookingId;
    }

    @Test
    void startSagaHappyPath() {
        var req = new BookingOrchestrationController.StartSagaRequest(
            "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c123",
            "acc-0194f2e0-7b3e-7610-0284-5c26e8b0c456",
            "off-0194f2e0-7b3e-7610-0284-5c26e8b0c789",
            List.of("tvl-user1"),
            List.of("seg-001", "seg-002"));

        ResponseEntity<?> response = controller.startSaga(req, UUID.randomUUID().toString(), request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        var body = (BookingOrchestrationController.StartSagaResponse) response.getBody();
        assertNotNull(body.sagaId());
        assertTrue(body.sagaId().startsWith("saga-"));
        assertEquals("ord-0194f2e0-7b3e-7610-0284-5c26e8b0c123", body.journeyOrderId());
        assertEquals("RESERVING", body.status());
        assertNotNull(body.startedAt());
        assertTrue(eventPublisher.getPublished().size() >= 1);
    }

    @Test
    void startSagaReturnsIdempotentResponse() {
        var req = new BookingOrchestrationController.StartSagaRequest(
            "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c123",
            "acc-0194f2e0-7b3e-7610-0284-5c26e8b0c456",
            "off-0194f2e0-7b3e-7610-0284-5c26e8b0c789",
            List.of("tvl-user1"),
            List.of("seg-001"));
        String idempotencyKey = UUID.randomUUID().toString();

        ResponseEntity<?> first = controller.startSaga(req, idempotencyKey, request);
        eventPublisher.clear();
        ResponseEntity<?> second = controller.startSaga(req, idempotencyKey, request);

        assertEquals(HttpStatus.CREATED, first.getStatusCode());
        assertEquals(HttpStatus.CREATED, second.getStatusCode());
        assertEquals(first.getBody(), second.getBody());
        assertEquals(0, eventPublisher.getPublished().size());
    }

    @Test
    void reusedIdempotencyKeyWithDifferentBodyReturns422() {
        String idempotencyKey = UUID.randomUUID().toString();
        var first = new BookingOrchestrationController.StartSagaRequest("ord-1", "acc-1", "off-1", List.of("tvl-1"), List.of("seg-1"));
        var second = new BookingOrchestrationController.StartSagaRequest("ord-2", "acc-1", "off-1", List.of("tvl-1"), List.of("seg-1"));
        controller.startSaga(first, idempotencyKey, request);

        var response = controller.handleIdempotencyKeyReused(
            assertThrowsReused(() -> controller.startSaga(second, idempotencyKey, request)), request);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals("IDEMPOTENCY_KEY_REUSED", response.getBody().code());
    }

    @Test
    void startSagaValidationFailure() {
        var req = new BookingOrchestrationController.StartSagaRequest("", "acc-123", "off-123", List.of(), null);
        ResponseEntity<?> response = controller.startSaga(req, UUID.randomUUID().toString(), request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        var error = (BookingOrchestrationController.ErrorBody) response.getBody();
        assertEquals("VALIDATION_FAILED", error.code());
    }

    @Test
    void startSagaRequiresIdempotencyKey() {
        var req = new BookingOrchestrationController.StartSagaRequest("ord-1", "acc-1", "off-1", List.of("tvl-1"), List.of("seg-1"));
        ResponseEntity<?> response = controller.startSaga(req, null, request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        var error = (BookingOrchestrationController.ErrorBody) response.getBody();
        assertEquals("VALIDATION_FAILED", error.code());
        assertTrue(error.message().contains("Idempotency-Key"));
    }

    @Test
    void getSagaReturnsSagaDetail() {
        String sagaId = createTestSaga();
        ResponseEntity<?> response = controller.getSaga(sagaId, request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody() instanceof BookingOrchestrationService.SagaDetail);
    }

    @Test
    void getSagaNotFound() {
        ResponseEntity<?> response = controller.getSaga("saga-nonexistent", request);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        var error = (BookingOrchestrationController.ErrorBody) response.getBody();
        assertEquals("NOT_FOUND", error.code());
    }

    @Test
    void requestReservationHappyPath() {
        String sagaId = createTestSaga();
        var req = new BookingOrchestrationController.RequestReservationRequest("seg-001", "tvl-user1", "sb-" + UUID.randomUUID());
        ResponseEntity<?> response = controller.requestReservation(sagaId, req, UUID.randomUUID().toString(), request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (BookingOrchestrationController.RequestReservationResponse) response.getBody();
        assertEquals("REQUESTED", body.status());
        assertTrue(eventPublisher.getPublished().size() >= 1);
    }

    @Test
    void requestReservationIdempotentReplay() {
        String sagaId = createTestSaga();
        var req = new BookingOrchestrationController.RequestReservationRequest("seg-001", "tvl-user1", "sb-" + UUID.randomUUID());
        String idempotencyKey = UUID.randomUUID().toString();
        ResponseEntity<?> first = controller.requestReservation(sagaId, req, idempotencyKey, request);
        eventPublisher.clear();
        ResponseEntity<?> second = controller.requestReservation(sagaId, req, idempotencyKey, request);
        assertEquals(first.getBody(), second.getBody());
        assertEquals(0, eventPublisher.getPublished().size());
    }

    @Test
    void requestReservationValidationFailure() {
        String sagaId = createTestSaga();
        var req = new BookingOrchestrationController.RequestReservationRequest("", null, "");
        ResponseEntity<?> response = controller.requestReservation(sagaId, req, UUID.randomUUID().toString(), request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        var error = (BookingOrchestrationController.ErrorBody) response.getBody();
        assertEquals("VALIDATION_FAILED", error.code());
    }

    @Test
    void markTicketedHappyPath() {
        String sagaId = createTestSaga();
        String segmentBookingId = createConfirmedSegment(sagaId);

        var req = new BookingOrchestrationController.MarkTicketedRequest(segmentBookingId, "ent-" + UUID.randomUUID());
        ResponseEntity<?> response = controller.markTicketed(sagaId, req, UUID.randomUUID().toString(), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (BookingOrchestrationController.MarkTicketedResponse) response.getBody();
        assertEquals("TICKETED", body.status());
        assertTrue(eventPublisher.getPublished().stream().anyMatch(envelope -> envelope.eventType().equals("SegmentTicketed")));
    }

    @Test
    void markTicketedEnforcesPreconditionForUnconfirmedBooking() {
        String sagaId = createTestSaga();
        String segmentBookingId = "sb-" + UUID.randomUUID();
        controller.requestReservation(sagaId,
            new BookingOrchestrationController.RequestReservationRequest("seg-001", "tvl-user1", segmentBookingId),
            UUID.randomUUID().toString(), request);

        var req = new BookingOrchestrationController.MarkTicketedRequest(segmentBookingId, "ent-" + UUID.randomUUID());
        var response = controller.handlePreconditionFailed(
            assertThrowsPrecondition(() -> controller.markTicketed(sagaId, req, UUID.randomUUID().toString(), request)), request);

        assertEquals(HttpStatus.PRECONDITION_FAILED, response.getStatusCode());
        assertEquals("PRECONDITION_FAILED", response.getBody().code());
    }

    @Test
    void markTicketedValidationFailure() {
        String sagaId = createTestSaga();
        var req = new BookingOrchestrationController.MarkTicketedRequest("", "");
        ResponseEntity<?> response = controller.markTicketed(sagaId, req, UUID.randomUUID().toString(), request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        var error = (BookingOrchestrationController.ErrorBody) response.getBody();
        assertEquals("VALIDATION_FAILED", error.code());
    }

    @Test
    void markTicketedNotFound() {
        var req = new BookingOrchestrationController.MarkTicketedRequest("sb-123", "ent-123");
        var response = controller.handleNotFound(
            assertThrowsNotFound(() -> controller.markTicketed("saga-nonexistent", req, UUID.randomUUID().toString(), request)), request);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        var error = response.getBody();
        assertEquals("NOT_FOUND", error.code());
    }

    @Test
    void publisherWrapsEventInCorrectEnvelope() {
        var req = new BookingOrchestrationController.StartSagaRequest("ord-123", "acc-456", "off-789", List.of("tvl-user1"), List.of("seg-001"));
        controller.startSaga(req, UUID.randomUUID().toString(), request);
        var envelope = eventPublisher.getPublished().getFirst();
        assertTrue(envelope.eventId().startsWith("evt-"));
        assertEquals("booking-orchestration", envelope.producer());
        assertEquals(1, envelope.schemaVersion());
        assertEquals(request.getAttribute(RequestContextFilter.CORRELATION_ID_HEADER), envelope.correlationId());
        assertNotNull(envelope.occurredAt());
        assertNotNull(envelope.payload());
    }

    @Test
    void errorBodyUsesRequestScopedCorrelationId() {
        request.addHeader(RequestContextFilter.CORRELATION_ID_HEADER, "corr-header");
        request.setAttribute(RequestContextFilter.CORRELATION_ID_HEADER, "corr-scoped");
        var req = new BookingOrchestrationController.StartSagaRequest(null, null, null, null, null);
        ResponseEntity<?> response = controller.startSaga(req, UUID.randomUUID().toString(), request);
        var error = (BookingOrchestrationController.ErrorBody) response.getBody();
        assertEquals("corr-scoped", error.correlationId());
    }

    private static BookingOrchestrationService.IdempotencyKeyReusedException assertThrowsReused(Runnable runnable) {
        try {
            runnable.run();
        } catch (BookingOrchestrationService.IdempotencyKeyReusedException ex) {
            return ex;
        }
        throw new AssertionError("expected IdempotencyKeyReusedException");
    }

    private static BookingOrchestrationService.PreconditionFailedException assertThrowsPrecondition(Runnable runnable) {
        try {
            runnable.run();
        } catch (BookingOrchestrationService.PreconditionFailedException ex) {
            return ex;
        }
        throw new AssertionError("expected PreconditionFailedException");
    }

    private static BookingOrchestrationService.NotFoundException assertThrowsNotFound(Runnable runnable) {
        try {
            runnable.run();
        } catch (BookingOrchestrationService.NotFoundException ex) {
            return ex;
        }
        throw new AssertionError("expected NotFoundException");
    }

    private static final class TestEventPublisher implements com.trainticket.bookingorchestration.application.EventPublisher {
        private final java.util.List<com.trainticket.platformkit.messaging.EventEnvelope> published = new java.util.ArrayList<>();

        @Override
        public void publish(com.trainticket.platformkit.messaging.EventEnvelope envelope) {
            published.add(envelope);
        }

        java.util.List<com.trainticket.platformkit.messaging.EventEnvelope> getPublished() {
            return java.util.List.copyOf(published);
        }

        void clear() {
            published.clear();
        }
    }

}
