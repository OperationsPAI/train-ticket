package com.trainticket.financesettlement.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

    private static final class RecordingPublisher implements EventPublisher {
        private final List<EventEnvelope> published = new ArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            published.add(envelope);
        }
    }
}
