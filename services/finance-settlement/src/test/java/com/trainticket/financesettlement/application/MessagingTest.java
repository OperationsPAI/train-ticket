package com.trainticket.financesettlement.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagingTest {
    @Test
    void publisherWrapsDomainEventInCanonicalEnvelope() {
        RecordingPublisher publisher = new RecordingPublisher();
        FinanceSettlementApplicationService service = new FinanceSettlementApplicationService(
            new InMemoryRevenueRepository(), new InMemoryReconciliationRepository(), publisher, new DomainEventEnvelopeMapper());
        RevenueRecognition recognition = RevenueRecognition.recognize(
            "ord-1", "item-1", "fare", Money.of("CNY", "10.00"), "policy-v1", "evt-source",
            Instant.parse("2026-07-05T10:00:00Z"), Instant.parse("2026-07-05T10:00:01Z"), "cmd-recognize", "corr-test");

        service.saveAndPublish(recognition);

        EventEnvelope envelope = publisher.published.getFirst();
        assertTrue(envelope.eventId().startsWith("evt-"));
        assertEquals("RevenueRecognized", envelope.eventType());
        assertEquals("finance-settlement", envelope.producer());
        assertEquals("corr-test", envelope.correlationId());
        assertEquals("cmd-recognize", envelope.causationId());
        assertEquals(1, envelope.schemaVersion());
        assertEquals(8, EventEnvelope.class.getRecordComponents().length);
        assertEquals(1000L, ((Map<?, ?>) envelope.payload().get("amount")).get("minorUnits"));
    }

    @Test
    void eventEnvelopeAcceptsMissingOptionalCausationId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = """
            {
              \"eventId\": \"evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222\",
              \"eventType\": \"PaymentCaptured\",
              \"schemaVersion\": 1,
              \"producer\": \"payment\",
              \"correlationId\": \"corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444\",
              \"occurredAt\": \"2026-07-03T10:30:00.000Z\",
              \"payload\": {
                \"paymentIntentId\": \"pi-0194f2e0-7b3e-7610-0284-5c26e8b0c789\",
                \"capturedAmount\": {\"currency\": \"CNY\", \"minorUnits\": 35000},
                \"channel\": \"wechat_pay\",
                \"channelTransactionId\": \"wx_txn_20260703_a1b2c3\"
              }
            }
            """;

        EventEnvelope envelope = objectMapper.readValue(json, EventEnvelope.class);

        assertEquals("evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222", envelope.eventId());
        assertNull(envelope.causationId());
    }

    @Test
    void subscriberHandlerDeduplicatesDuplicateEventId() {
        InMemoryConsumedEventLogRepository repository = new InMemoryConsumedEventLogRepository();
        FinanceSettlementEventHandler handler = new FinanceSettlementEventHandler(
            repository,
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC)
        );
        EventEnvelope envelope = new EventEnvelope(
            "evt-duplicate", "PaymentCaptured", Instant.parse("2026-07-05T09:59:00Z"), "corr-test", "evt-source",
            "payment", 1, Map.of("paymentIntentId", "pi-1"));

        assertEquals(HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(1, repository.saveCount);
    }

    @Test
    void handlerConsumesPaymentIntentCreatedThenPaymentCapturedUsingContractPayloadFields() {
        InMemoryConsumedEventLogRepository consumedEvents = new InMemoryConsumedEventLogRepository();
        PaymentIntentOrderReferenceRepository orderReferences = new InMemoryPaymentIntentOrderReferenceRepository();
        RecordingPublisher publisher = new RecordingPublisher();
        InMemoryRevenueRepository revenueRepository = new InMemoryRevenueRepository();
        FinanceSettlementApplicationService service = new FinanceSettlementApplicationService(
            revenueRepository, new InMemoryReconciliationRepository(), publisher, new DomainEventEnvelopeMapper());
        FinanceSettlementEventHandler handler = new FinanceSettlementEventHandler(
            consumedEvents,
            orderReferences,
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC),
            service
        );

        HandlerResult createdResult = handler.handle(new EventEnvelope(
            "evt-intent-created", "PaymentIntentCreated", Instant.parse("2026-07-03T10:29:00Z"), "corr-payment", null,
            "payment", 1, Map.of(
                "paymentIntentId", "pi-0194f2e0-7b3e-7610-0284-5c26e8b0c789",
                "businessRef", "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c321",
                "purpose", "purchase",
                "amount", Map.of("currency", "CNY", "minorUnits", 35000),
                "payerRef", "acct-0194f2e0-7b3e-7610-0284-5c26e8b0c654",
                "idempotencyKey", "idem-0194f2e0-7b3e-7610-0284-5c26e8b0c777",
                "createdAt", "2026-07-03T10:29:00Z"
            )));
        HandlerResult capturedResult = handler.handle(new EventEnvelope(
            "evt-captured", "PaymentCaptured", Instant.parse("2026-07-03T10:30:00Z"), "corr-payment", null,
            "payment", 1, Map.of(
                "paymentIntentId", "pi-0194f2e0-7b3e-7610-0284-5c26e8b0c789",
                "capturedAmount", Map.of("currency", "CNY", "minorUnits", 35000),
                "channel", "wechat_pay",
                "channelTransactionId", "wx_txn_20260703_a1b2c3"
            )));

        assertEquals(HandlerResult.SUCCESS, createdResult);
        assertEquals(HandlerResult.SUCCESS, capturedResult);
        EventEnvelope published = publisher.published.getFirst();
        assertEquals("RevenueRecognized", published.eventType());
        assertEquals("ord-0194f2e0-7b3e-7610-0284-5c26e8b0c321", published.payload().get("orderId"));
        assertEquals("pi-0194f2e0-7b3e-7610-0284-5c26e8b0c789", published.payload().get("orderItemId"));
        assertEquals(35000L, ((Map<?, ?>) published.payload().get("amount")).get("minorUnits"));
    }

    @Test
    void paymentCapturedWithUnknownIntentIsTransientAndNotConsumed() {
        InMemoryConsumedEventLogRepository consumedEvents = new InMemoryConsumedEventLogRepository();
        FinanceSettlementApplicationService service = new FinanceSettlementApplicationService(
            new InMemoryRevenueRepository(), new InMemoryReconciliationRepository(), new RecordingPublisher(), new DomainEventEnvelopeMapper());
        FinanceSettlementEventHandler handler = new FinanceSettlementEventHandler(
            consumedEvents,
            new InMemoryPaymentIntentOrderReferenceRepository(),
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC),
            service
        );

        EventEnvelope captured = new EventEnvelope(
            "evt-out-of-order", "PaymentCaptured", Instant.parse("2026-07-03T10:30:00Z"), "corr-payment", "cmd-payment",
            "payment", 1, Map.of(
                "paymentIntentId", "pi-unknown",
                "capturedAmount", Map.of("currency", "CNY", "minorUnits", 35000),
                "channel", "wechat_pay",
                "channelTransactionId", "wx_txn_20260703_a1b2c3"
            ));

        assertEquals(HandlerResult.TRANSIENT_FAILURE, handler.handle(captured));
        assertEquals(0, consumedEvents.saveCount);
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final List<EventEnvelope> published = new ArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            published.add(envelope);
        }
    }
}
