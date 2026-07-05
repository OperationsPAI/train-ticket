package com.trainticket.bookingorchestration.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.bookingorchestration.domain.BookingEvent;
import com.trainticket.bookingorchestration.domain.BookingSagaStatus;
import com.trainticket.bookingorchestration.domain.ProviderReference;
import com.trainticket.bookingorchestration.domain.SegmentBookingEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BookingOrchestrationServicePayloadContractTest {

    private BookingOrchestrationService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        service = new BookingOrchestrationService(
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC),
            envelope -> { });
        objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @Test
    void mapsBookingSagaStartedPayloadFields() {
        var contract = service.toContractEvent(new BookingEvent.BookingSagaStarted(
            "domain-event-1", "saga-123", Instant.parse("2026-07-05T10:00:00Z"), "ord-123")).orElseThrow();

        assertEquals("BookingSagaStarted", contract.eventType());
        var payload = assertInstanceOf(BookingOrchestrationService.BookingSagaStartedPayload.class, contract.payload());
        assertEquals("saga-123", payload.sagaId());
        assertEquals("ord-123", payload.journeyOrderId());
        assertEquals(Instant.parse("2026-07-05T10:00:00Z"), payload.startedAt());
    }

    @Test
    void mapsSegmentReservationRequestedPayloadFields() {
        var contract = service.toContractEvent(new SegmentBookingEvent.SegmentReservationRequested(
            "domain-event-2", "sb-123", Instant.parse("2026-07-05T10:00:01Z"),
            "ord-123", "seg-001", "tvl-123", "idem-123")).orElseThrow();

        assertEquals("SegmentReservationRequested", contract.eventType());
        var payload = assertInstanceOf(BookingOrchestrationService.SegmentReservationRequestedPayload.class, contract.payload());
        assertEquals("sb-123", payload.segmentBookingId());
        assertEquals("ord-123", payload.journeyOrderId());
        assertEquals("seg-001", payload.segmentRef());
        assertEquals("tvl-123", payload.travelerRef());
        assertEquals("idem-123", payload.idempotencyKey());
    }

    @Test
    void mapsSegmentCapacityHoldingPayloadFields() {
        var contract = service.toContractEvent(new SegmentBookingEvent.SegmentCapacityHolding(
            "domain-event-3", "sb-123", Instant.parse("2026-07-05T10:00:02Z"), "hold-123")).orElseThrow();

        assertEquals("SegmentCapacityHolding", contract.eventType());
        var payload = assertInstanceOf(BookingOrchestrationService.SegmentCapacityHoldingPayload.class, contract.payload());
        assertEquals("sb-123", payload.segmentBookingId());
        assertEquals("hold-123", payload.capacityHoldId());
    }

    @Test
    void mapsSegmentReservationConfirmedPayloadFieldsWithOptionalProviderReference() {
        var providerReference = new ProviderReference("provider-1", "res-1", "PNR1");
        var contract = service.toContractEvent(new SegmentBookingEvent.SegmentReservationConfirmed(
            "domain-event-4", "sb-123", Instant.parse("2026-07-05T10:00:03Z"),
            Optional.of(providerReference), "confirmed")).orElseThrow();

        assertEquals("SegmentReservationConfirmed", contract.eventType());
        var payload = assertInstanceOf(BookingOrchestrationService.SegmentReservationConfirmedPayload.class, contract.payload());
        assertEquals("sb-123", payload.segmentBookingId());
        assertEquals(providerReference, payload.providerReference());
        assertEquals("confirmed", payload.evidence());
    }

    @Test
    void mapsSegmentReservationConfirmedPayloadFieldsWithoutProviderReference() throws JsonProcessingException {
        var contract = service.toContractEvent(new SegmentBookingEvent.SegmentReservationConfirmed(
            "domain-event-5", "sb-123", Instant.parse("2026-07-05T10:00:04Z"),
            Optional.empty(), "internal-confirmed")).orElseThrow();

        String payloadJson = objectMapper.writeValueAsString(contract.payload());

        assertEquals("{\"segmentBookingId\":\"sb-123\",\"evidence\":\"internal-confirmed\"}", payloadJson);
    }

    @Test
    void mapsSegmentReservationFailedPayloadFields() {
        var contract = service.toContractEvent(new SegmentBookingEvent.SegmentReservationFailed(
            "domain-event-6", "sb-123", Instant.parse("2026-07-05T10:00:05Z"), "sold out")).orElseThrow();

        assertEquals("SegmentReservationFailed", contract.eventType());
        var payload = assertInstanceOf(BookingOrchestrationService.SegmentReservationFailedPayload.class, contract.payload());
        assertEquals("sb-123", payload.segmentBookingId());
        assertEquals("sold out", payload.reason());
    }

    @Test
    void mapsSegmentBookingCancelledPayloadFields() {
        var contract = service.toContractEvent(new SegmentBookingEvent.SegmentBookingCancelled(
            "domain-event-7", "sb-123", Instant.parse("2026-07-05T10:00:06Z"), "customer request")).orElseThrow();

        assertEquals("SegmentBookingCancelled", contract.eventType());
        var payload = assertInstanceOf(BookingOrchestrationService.SegmentBookingCancelledPayload.class, contract.payload());
        assertEquals("sb-123", payload.segmentBookingId());
        assertEquals("customer request", payload.reason());
    }

    @Test
    void mapsSegmentTicketedPayloadFields() {
        var contract = service.toContractEvent(new SegmentBookingEvent.SegmentTicketed(
            "domain-event-8", "sb-123", Instant.parse("2026-07-05T10:00:07Z"), "ent-123")).orElseThrow();

        assertEquals("SegmentTicketed", contract.eventType());
        var payload = assertInstanceOf(BookingOrchestrationService.SegmentTicketedPayload.class, contract.payload());
        assertEquals("sb-123", payload.segmentBookingId());
        assertEquals("ent-123", payload.entitlementId());
    }

    @Test
    void mapsBookingSagaCompletedPayloadFields() {
        var start = service.startSaga(new BookingOrchestrationService.StartSagaCommand(
            "ord-123", "acc-123", "off-123", List.of("tvl-123"), List.of("seg-001")),
            "idem-start-completed", "corr-123");

        var contract = service.toContractEvent(new BookingEvent.BookingSagaCompleted(
            "domain-event-9", start.sagaId(), Instant.parse("2026-07-05T10:00:08Z"))).orElseThrow();

        assertEquals("BookingSagaCompleted", contract.eventType());
        var payload = assertInstanceOf(BookingOrchestrationService.BookingSagaCompletedPayload.class, contract.payload());
        assertEquals(start.sagaId(), payload.sagaId());
        assertEquals("ord-123", payload.journeyOrderId());
    }

    @Test
    void mapsBookingSagaFailedPayloadFields() {
        var start = service.startSaga(new BookingOrchestrationService.StartSagaCommand(
            "ord-456", "acc-123", "off-123", List.of("tvl-123"), List.of("seg-001")),
            "idem-start-failed", "corr-123");

        var contract = service.toContractEvent(new BookingEvent.BookingSagaFailed(
            "domain-event-10", start.sagaId(), Instant.parse("2026-07-05T10:00:09Z"),
            "capacity unavailable")).orElseThrow();

        assertEquals("BookingSagaFailed", contract.eventType());
        var payload = assertInstanceOf(BookingOrchestrationService.BookingSagaFailedPayload.class, contract.payload());
        assertEquals(start.sagaId(), payload.sagaId());
        assertEquals("ord-456", payload.journeyOrderId());
        assertEquals("capacity unavailable", payload.reason());
    }

    @Test
    void ignoresInternalSagaEventsThatAreNotInTheBookingOrchestrationEventContract() {
        assertTrue(service.toContractEvent(new BookingEvent.BookingSagaAdvanced(
            "domain-event-internal-1", "saga-123", Instant.parse("2026-07-05T10:00:08Z"),
            BookingSagaStatus.RESERVING)).isEmpty());
        assertTrue(service.toContractEvent(new BookingEvent.BookingSagaStepSucceeded(
            "domain-event-internal-2", "saga-123", Instant.parse("2026-07-05T10:00:09Z"),
            "reserve-seg-001", "idem-123")).isEmpty());
    }

    @Test
    void paymentCapturedPayloadFromContractAdvancesSagaByStoredPaymentIntentId() {
        var published = new TestEventPublisher();
        var service = new BookingOrchestrationService(
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC), published);
        var start = service.startSaga(new BookingOrchestrationService.StartSagaCommand(
            "ord-0194f2e0-7b3e-7610-8284-5c26e8b0c123", "acc-123", "off-123", List.of("tvl-123"), List.of("seg-001")),
            "idem-start-payment", "corr-start");
        published.clear();

        assertInstanceOf(HandlerResult.Success.class, service.handleUpstreamEvent(new EventEnvelope("evt-0194f2e0-7b3e-7610-8284-5c26e8b0c201", "PaymentIntentCreated", Instant.parse("2026-07-05T10:01:00Z"), "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c203", "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c202", "payment", 1, Map.of(
                "paymentIntentId", "pi-0194f2e0-7b3e-7610-8284-5c26e8b0c204",
                "businessRef", "ord-0194f2e0-7b3e-7610-8284-5c26e8b0c123",
                "purpose", "purchase",
                "amount", Map.of("currency", "CNY", "minorUnits", 35000),
                "payerRef", "acc-123",
                "idempotencyKey", "payment-idem-123",
                "createdAt", "2026-07-05T10:01:00Z"))));

        assertInstanceOf(HandlerResult.Success.class, service.handleUpstreamEvent(new EventEnvelope("evt-0194f2e0-7b3e-7610-8284-5c26e8b0c211", "PaymentCaptured", Instant.parse("2026-07-05T10:02:00Z"), "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c203", "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c201", "payment", 1, Map.of(
                "paymentIntentId", "pi-0194f2e0-7b3e-7610-8284-5c26e8b0c204",
                "capturedAmount", Map.of("currency", "CNY", "minorUnits", 35000),
                "channel", "wechat_pay",
                "channelTransactionId", "wx_txn_20260703_a1b2c3"))));

        var detail = service.getSaga(start.sagaId()).orElseThrow();
        assertEquals("TICKETING", detail.status());
    }

    @Test
    void capacityReleasePayloadFromContractAdvancesSegmentByStoredHoldId() {
        var published = new TestEventPublisher();
        var service = new BookingOrchestrationService(
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC), published);
        var start = service.startSaga(new BookingOrchestrationService.StartSagaCommand(
            "ord-capacity", "acc-123", "off-123", List.of("tvl-123"), List.of("seg-001")),
            "idem-start-capacity", "corr-start");
        service.requestReservation(start.sagaId(), new BookingOrchestrationService.RequestReservationCommand(
            "seg-001", "tvl-123", "sb-0194f2e0-7b3e-7610-8284-5c26e8b0c301"),
            "idem-reservation-capacity", "corr-start");
        published.clear();

        assertInstanceOf(HandlerResult.Success.class, service.handleUpstreamEvent(new EventEnvelope("evt-0194f2e0-7b3e-7610-8284-5c26e8b0c302", "CapacityHeld", Instant.parse("2026-07-05T10:03:00Z"), "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c304", "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c303", "capacity-availability", 1, Map.of(
                "holdId", "hold-0194f2e0-7b3e-7610-8284-5c26e8b0c305",
                "inventoryPoolId", "pool-123",
                "capacityUnitRef", "cu-123",
                "interval", Map.of("fromStationRef", "BJP", "toStationRef", "SHH"),
                "idempotencyKey", "ord-capacity:seg-001:tvl-123:purchase",
                "expiresAt", "2026-07-05T10:08:00Z",
                "idempotentReplay", false))));
        published.clear();

        assertInstanceOf(HandlerResult.Success.class, service.handleUpstreamEvent(new EventEnvelope("evt-0194f2e0-7b3e-7610-8284-5c26e8b0c306", "CapacityReleased", Instant.parse("2026-07-05T10:04:00Z"), "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c304", "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c307", "capacity-availability", 1, Map.of(
                "holdId", "hold-0194f2e0-7b3e-7610-8284-5c26e8b0c305",
                "inventoryPoolId", "pool-123",
                "capacityUnitRef", "cu-123",
                "interval", Map.of("fromStationRef", "BJP", "toStationRef", "SHH"),
                "releasedAt", "2026-07-05T10:04:00Z",
                "releaseReason", "payment-expired"))));

        assertTrue(published.getPublished().stream().anyMatch(envelope -> envelope.eventType().equals("SegmentBookingCancelled")));
    }

    @Test
    void failurePathsPropagateConsumedEnvelopeCorrelationId() {
        var published = new TestEventPublisher();
        var service = new BookingOrchestrationService(
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC), published);
        var start = service.startSaga(new BookingOrchestrationService.StartSagaCommand(
            "ord-provider-failure", "acc-123", "off-123", List.of("tvl-123"), List.of("seg-001")),
            "idem-start-provider-failure", "corr-start");
        service.requestReservation(start.sagaId(), new BookingOrchestrationService.RequestReservationCommand(
            "seg-001", "tvl-123", "sb-0194f2e0-7b3e-7610-8284-5c26e8b0c401"),
            "idem-reservation-provider-failure", "corr-start");
        published.clear();

        assertInstanceOf(HandlerResult.Success.class, service.handleUpstreamEvent(new EventEnvelope("evt-0194f2e0-7b3e-7610-8284-5c26e8b0c402", "ProviderReservationFailed", Instant.parse("2026-07-05T10:05:00Z"), "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c404", "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c403", "provider-integration", 1, Map.of("segmentBookingId", "sb-0194f2e0-7b3e-7610-8284-5c26e8b0c401", "reason", "provider-unavailable"))));

        assertTrue(published.getPublished().stream().anyMatch(envelope ->
            envelope.eventType().equals("SegmentReservationFailed")
                && "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c404".equals(envelope.correlationId())));
    }

    @Test
    void entitlementIssueFailedPayloadFromContractAdvancesSagaFailureByEnvelopeCorrelationIdAndCausationId() {
        var published = new TestEventPublisher();
        var service = new BookingOrchestrationService(
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC), published);
        var start = service.startSaga(new BookingOrchestrationService.StartSagaCommand(
            "ord-entitlement-failure", "acc-123", "off-123", List.of("tvl-123"), List.of("seg-001")),
            "idem-start-entitlement-failure", "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c501");
        service.requestReservation(start.sagaId(), new BookingOrchestrationService.RequestReservationCommand(
            "seg-001", "tvl-123", "sb-0194f2e0-7b3e-7610-8284-5c26e8b0c502"),
            "idem-reservation-entitlement-failure", "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c501");
        published.clear();

        assertInstanceOf(HandlerResult.Success.class, service.handleUpstreamEvent(new EventEnvelope("evt-0194f2e0-7b3e-7610-8284-5c26e8b0c503", "EntitlementIssueFailed", Instant.parse("2026-07-05T10:06:00Z"), "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c501", "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c504", "entitlement-ticketing", 1, Map.of(
                "entitlementId", "ent-0194f2e0-7b3e-7610-8284-5c26e8b0c505",
                "retryable", false,
                "failureCode", "PROVIDER_REJECTED",
                "failureMessage", "provider rejected ticket issue",
                "failedAt", "2026-07-05T10:06:00Z"))));

        var detail = service.getSaga(start.sagaId()).orElseThrow();
        assertEquals("FAILED", detail.status());
        assertTrue(published.getPublished().stream().anyMatch(envelope ->
            envelope.eventType().equals("SegmentReservationFailed")
                && "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c501".equals(envelope.correlationId())
                && "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c503".equals(envelope.causationId())));
        assertTrue(published.getPublished().stream().anyMatch(envelope ->
            envelope.eventType().equals("BookingSagaFailed")
                && "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c501".equals(envelope.correlationId())
                && "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c503".equals(envelope.causationId())));
    }

    @Test
    void entitlementIssueFailedWithUnknownCorrelationIdIsAcknowledgedWithoutPublishing() {
        var published = new TestEventPublisher();
        var service = new BookingOrchestrationService(
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC), published);

        assertInstanceOf(HandlerResult.Success.class, service.handleUpstreamEvent(new EventEnvelope("evt-0194f2e0-7b3e-7610-8284-5c26e8b0c601", "EntitlementIssueFailed", Instant.parse("2026-07-05T10:07:00Z"), "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c604", "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c602", "entitlement-ticketing", 1, Map.of(
                "entitlementId", "ent-0194f2e0-7b3e-7610-8284-5c26e8b0c603",
                "retryable", true,
                "failureCode", "TEMPORARY_PROVIDER_TIMEOUT",
                "failureMessage", "provider timeout",
                "failedAt", "2026-07-05T10:07:00Z"))));

        assertTrue(published.getPublished().isEmpty());
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
