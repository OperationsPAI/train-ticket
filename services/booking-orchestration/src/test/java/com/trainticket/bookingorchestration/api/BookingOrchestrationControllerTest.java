package com.trainticket.bookingorchestration.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.bookingorchestration.adapters.messaging.InMemoryEventPublisher;
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

    private Clock clock;
    private InMemoryEventPublisher eventPublisher;
    private BookingOrchestrationController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC);
        eventPublisher = new InMemoryEventPublisher();
        controller = new BookingOrchestrationController(clock, eventPublisher);
        request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "corr-" + UUID.randomUUID());
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
        assertTrue(response.getBody() instanceof BookingOrchestrationController.StartSagaResponse);
        var body = (BookingOrchestrationController.StartSagaResponse) response.getBody();
        assertNotNull(body.sagaId());
        assertTrue(body.sagaId().startsWith("saga-"));
        assertEquals("ord-0194f2e0-7b3e-7610-0284-5c26e8b0c123", body.journeyOrderId());
        assertEquals("RESERVING", body.status());
        assertNotNull(body.startedAt());
        assertTrue(eventPublisher.getPublished().size() >= 2);
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
    void startSagaValidationFailure() {
        var req = new BookingOrchestrationController.StartSagaRequest(
            "", "acc-123", "off-123", List.of(), null);

        ResponseEntity<?> response = controller.startSaga(req, UUID.randomUUID().toString(), request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        var error = (BookingOrchestrationController.ErrorBody) response.getBody();
        assertEquals("VALIDATION_FAILED", error.code());
    }

    @Test
    void getSagaReturnsSagaDetail() {
        String sagaId = createTestSaga();

        ResponseEntity<?> response = controller.getSaga(sagaId, request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody() instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) response.getBody();
        assertEquals(sagaId, detail.get("sagaId"));
        assertNotNull(detail.get("steps"));
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

        var req = new BookingOrchestrationController.RequestReservationRequest(
            "seg-001", "tvl-user1", "sb-" + UUID.randomUUID());
        ResponseEntity<?> response = controller.requestReservation(
            sagaId, req, UUID.randomUUID().toString(), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (BookingOrchestrationController.RequestReservationResponse) response.getBody();
        assertEquals("REQUESTED", body.status());
        assertTrue(eventPublisher.getPublished().size() >= 1);
    }

    @Test
    void requestReservationIdempotentReplay() {
        String sagaId = createTestSaga();

        var req = new BookingOrchestrationController.RequestReservationRequest(
            "seg-001", "tvl-user1", "sb-" + UUID.randomUUID());
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
        ResponseEntity<?> response = controller.requestReservation(
            sagaId, req, UUID.randomUUID().toString(), request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        var error = (BookingOrchestrationController.ErrorBody) response.getBody();
        assertEquals("VALIDATION_FAILED", error.code());
    }

    @Test
    void markTicketedHappyPath() {
        String sagaId = createTestSaga();

        var req = new BookingOrchestrationController.MarkTicketedRequest(
            "sb-0194f2e0-7b3e-7610-0284-5c26e8b0cabc",
            "ent-0194f2e0-7b3e-7610-0284-5c26e8b0cdef");
        ResponseEntity<?> response = controller.markTicketed(
            sagaId, req, UUID.randomUUID().toString(), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (BookingOrchestrationController.MarkTicketedResponse) response.getBody();
        assertEquals("TICKETED", body.status());
    }

    @Test
    void markTicketedIdempotentReplay() {
        String sagaId = createTestSaga();

        var req = new BookingOrchestrationController.MarkTicketedRequest(
            "sb-0194f2e0-7b3e-7610-0284-5c26e8b0cabc",
            "ent-0194f2e0-7b3e-7610-0284-5c26e8b0cdef");
        String idempotencyKey = UUID.randomUUID().toString();

        ResponseEntity<?> first = controller.markTicketed(sagaId, req, idempotencyKey, request);
        ResponseEntity<?> second = controller.markTicketed(sagaId, req, idempotencyKey, request);

        assertEquals(first.getBody(), second.getBody());
    }

    @Test
    void markTicketedValidationFailure() {
        String sagaId = createTestSaga();
        var req = new BookingOrchestrationController.MarkTicketedRequest("", "");
        ResponseEntity<?> response = controller.markTicketed(
            sagaId, req, UUID.randomUUID().toString(), request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        var error = (BookingOrchestrationController.ErrorBody) response.getBody();
        assertEquals("VALIDATION_FAILED", error.code());
    }

    @Test
    void markTicketedNotFound() {
        var req = new BookingOrchestrationController.MarkTicketedRequest("sb-123", "ent-123");
        ResponseEntity<?> response = controller.markTicketed(
            "saga-nonexistent", req, UUID.randomUUID().toString(), request);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        var error = (BookingOrchestrationController.ErrorBody) response.getBody();
        assertEquals("NOT_FOUND", error.code());
    }

    @Test
    void publisherWrapsEventInCorrectEnvelope() {
        var req = new BookingOrchestrationController.StartSagaRequest(
            "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c123",
            "acc-456", "off-789", List.of("tvl-user1"), List.of("seg-001"));

        controller.startSaga(req, UUID.randomUUID().toString(), request);

        var published = eventPublisher.getPublished();
        assertTrue(published.size() >= 2);
        var envelope = published.get(0);
        assertTrue(envelope.eventId().startsWith("evt-"));
        assertEquals("booking-orchestration", envelope.producer());
        assertEquals(1, envelope.schemaVersion());
        assertNotNull(envelope.correlationId());
        assertNotNull(envelope.occurredAt());
        assertNotNull(envelope.payload());
    }

    @Test
    void errorBodyHasCanonicalShape() {
        var req = new BookingOrchestrationController.StartSagaRequest(null, null, null, null, null);
        ResponseEntity<?> response = controller.startSaga(req, UUID.randomUUID().toString(), request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        var error = (BookingOrchestrationController.ErrorBody) response.getBody();
        assertEquals("VALIDATION_FAILED", error.code());
        assertNotNull(error.message());
        assertNotNull(error.correlationId());
        assertNotNull(error.details());
    }
}
