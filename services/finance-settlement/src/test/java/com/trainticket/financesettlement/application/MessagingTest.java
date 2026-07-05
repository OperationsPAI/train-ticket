package com.trainticket.financesettlement.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(1000L, ((java.util.Map<?, ?>) envelope.payload().get("amount")).get("minorUnits"));
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
            "payment", 1, java.util.Map.of("paymentIntentId", "pi-1"));

        assertEquals(HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(1, repository.saveCount);
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
        assertEquals(null, envelope.causationId());
    }

    @Test
    void paymentCapturedContractPayloadRecognizesCapturedAmount() {
        InMemoryRevenueRepository revenueRepository = new InMemoryRevenueRepository();
        RecordingPublisher publisher = new RecordingPublisher();
        FinanceSettlementApplicationService service = new FinanceSettlementApplicationService(
            revenueRepository, new InMemoryReconciliationRepository(), publisher, new DomainEventEnvelopeMapper());
        FinanceSettlementEventHandler handler = new FinanceSettlementEventHandler(
            new InMemoryConsumedEventLogRepository(),
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC),
            service
        );
        EventEnvelope envelope = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222",
            "PaymentCaptured",
            Instant.parse("2026-07-03T10:30:00.000Z"),
            "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444",
            "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555",
            "payment",
            1,
            Map.of(
                "orderId", "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c111",
                "paymentIntentId", "pi-0194f2e0-7b3e-7610-0284-5c26e8b0c789",
                "capturedAmount", Map.of("currency", "CNY", "minorUnits", 35000L),
                "channel", "wechat_pay",
                "channelTransactionId", "wx_txn_20260703_a1b2c3"
            )
        );

        assertEquals(HandlerResult.SUCCESS, handler.handle(envelope));

        assertEquals(1, publisher.published.size());
        EventEnvelope published = publisher.published.getFirst();
        assertEquals("RevenueRecognized", published.eventType());
        assertEquals(35000L, ((Map<?, ?>) published.payload().get("amount")).get("minorUnits"));
        assertEquals("evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222", published.payload().get("sourceEventId"));
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final List<EventEnvelope> published = new ArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            published.add(envelope);
        }
    }
}
