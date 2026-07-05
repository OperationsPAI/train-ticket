package com.trainticket.payment.application;

import com.trainticket.platformkit.messaging.EnvelopeFactory;
import com.trainticket.platformkit.messaging.EventEnvelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.payment.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentMessagingApplicationTest {
    @Test
    void publisherWrapsDomainEventsInContractEnvelope() {
        FakeEventPublisher publisher = new FakeEventPublisher();
        PaymentCommandService service = new PaymentCommandService(Clock.fixed(Instant.parse("2026-07-05T10:30:00Z"), ZoneOffset.UTC), publisher);

        service.createIntent("ord-123", "purchase", Money.fromMinorUnits(35000, "CNY"), "acct-1", "0194f2e0-7b3e-7610-0284-5c26e8b0c555", "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444");

        EventEnvelope envelope = publisher.published().getFirst();
        assertEquals("PaymentIntentCreated", envelope.eventType());
        assertEquals("payment", envelope.producer());
        assertEquals(1, envelope.schemaVersion());
        assertEquals("corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444", envelope.correlationId());
        assertEquals("cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555", envelope.causationId());
        assertEquals("2026-07-05T10:30:00Z", envelope.occurredAt().toString());
        Map<?, ?> payload = assertInstanceOf(Map.class, envelope.payload());
        assertEquals(Map.of("currency", "CNY", "minorUnits", 35000L), payload.get("amount"));
    }

    @Test
    void subscriberDeduplicatesDuplicateEventIds() {
        FakeEventPublisher publisher = new FakeEventPublisher();
        PaymentInboundEventHandler handler = new PaymentInboundEventHandler(
            new ConsumedEventDeduplicator(),
            new PaymentCommandService(Clock.fixed(Instant.parse("2026-07-05T10:30:00Z"), ZoneOffset.UTC), publisher)
        );
        FakeEventSubscriber subscriber = new FakeEventSubscriber();
        subscriber.subscribe(List.of("ignored"), "payment", "payment-test", handler);
        EventEnvelope envelope = segmentReservationRequested("evt-1", "idem-1");

        assertEquals(HandlerResult.SUCCESS, subscriber.emit(envelope));
        assertEquals(HandlerResult.SUCCESS, subscriber.emit(envelope));
        assertEquals(0, publisher.published().size());
    }

    @Test
    void segmentReservationRequestedRecordsConformantRequestWithoutInventingMoney() {
        FakeEventPublisher publisher = new FakeEventPublisher();
        PaymentCommandService commands = new PaymentCommandService(Clock.fixed(Instant.parse("2026-07-05T10:30:00Z"), ZoneOffset.UTC), publisher);
        PaymentInboundEventHandler handler = new PaymentInboundEventHandler(new ConsumedEventDeduplicator(), commands);

        assertEquals(HandlerResult.SUCCESS, handler.handle(segmentReservationRequested("evt-segment", "idem-segment")));

        ReservationPaymentRequest request = commands.getReservationPaymentRequest("sb-1");
        assertEquals("evt-segment", request.eventId());
        assertEquals("ord-1", request.journeyOrderId());
        assertEquals("seg-1", request.segmentRef());
        assertEquals("trav-1", request.travelerRef());
        assertEquals("idem-segment", request.idempotencyKey());
        assertTrue(publisher.published().isEmpty());
    }

    @Test
    void postSalesApprovedRequestsRefundThroughDomain() {
        FakeEventPublisher publisher = new FakeEventPublisher();
        PaymentCommandService commands = new PaymentCommandService(Clock.fixed(Instant.parse("2026-07-05T10:30:00Z"), ZoneOffset.UTC), publisher);
        String paymentIntentId = commands.createIntent("ord-refund", "purchase", Money.fromMinorUnits(1000, "CNY"), "trav-1", "idem-create", "corr-create").paymentIntentId();
        commands.captureIntent(paymentIntentId, "idem-capture", "corr-create");
        PaymentInboundEventHandler handler = new PaymentInboundEventHandler(new ConsumedEventDeduplicator(), commands);

        EventEnvelope approved = new EventEnvelope(
            "evt-refund",
            "PostSalesApproved",
            Instant.parse("2026-07-05T10:31:00Z"),
            "corr-refund",
            "evt-post-sales",
            "post-sales",
            1,
            Map.of(
                "caseId", "case-1",
                "orderId", "ord-refund",
                "approvedActions", Map.of("paymentIntentId", paymentIntentId, "refundAmount", Map.of("currency", "CNY", "minorUnits", 500L))
            )
        );

        assertEquals(HandlerResult.SUCCESS, handler.handle(approved));
        assertEquals("RefundRequested", publisher.published().getLast().eventType());
    }

    @Test
    void mapsRefundFailedPayloadToContractShape() {
        EventEnvelope envelope = EventEnvelopeMapper.fromDomainEvent(new com.trainticket.payment.domain.RefundFailed(
            EnvelopeFactory.create(
                "RefundFailed",
                Instant.parse("2026-07-05T10:32:00Z"),
                "evt-0194f2e0-7b3e-7610-0284-5c26e8b0c111",
                "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444",
                "payment"
            ),
            "rf-0194f2e0-7b3e-7610-0284-5c26e8b0c333",
            "pi-0194f2e0-7b3e-7610-0284-5c26e8b0c222",
            "ACCOUNT_CLOSED"
        ));

        assertEquals("RefundFailed", envelope.eventType());
        assertEquals(
            Map.of(
                "refundId", "rf-0194f2e0-7b3e-7610-0284-5c26e8b0c333",
                "paymentIntentId", "pi-0194f2e0-7b3e-7610-0284-5c26e8b0c222",
                "reason", "ACCOUNT_CLOSED"
            ),
            envelope.payload()
        );
    }

    private static EventEnvelope segmentReservationRequested(String eventId, String idempotencyKey) {
        return new EventEnvelope(
            eventId,
            "SegmentReservationRequested",
            Instant.parse("2026-07-05T10:30:00Z"),
            "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444",
            "evt-0194f2e0-7b3e-7610-0284-5c26e8b0c111",
            "booking-orchestration",
            1,
            Map.of(
                "segmentBookingId", "sb-1",
                "journeyOrderId", "ord-1",
                "segmentRef", "seg-1",
                "travelerRef", "trav-1",
                "idempotencyKey", idempotencyKey
            )
        );
    }
}
