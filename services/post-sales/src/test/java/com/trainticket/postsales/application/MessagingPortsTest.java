package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MessagingPortsTest {
    @Test
    void publisherWrapsDomainEventInContractEnvelope() throws Exception {
        PostSalesCase postSalesCase = PostSalesCase.open(
            "ord-test-2",
            PostSalesCaseType.REFUND,
            PostSalesScope.ticket("oi-test-2", "seg-test-2", "tvl-test-2", "ent-test-2"),
            "CUSTOMER_REQUEST",
            "acct-test-2",
            "cmd-open-2",
            Instant.parse("2026-07-05T10:30:00Z"),
            "open-2",
            "corr-2"
        );

        EventEnvelope envelope = PostSalesMapper.toEnvelope(postSalesCase.domainEvents().get(1));

        assertTrue(envelope.eventId().startsWith("evt-"));
        assertEquals("PostSalesRequested", envelope.eventType());
        assertEquals(1, envelope.schemaVersion());
        assertEquals("post-sales", envelope.producer());
        assertTrue(envelope.causationId().startsWith("cmd-"));
        assertTrue(envelope.correlationId().startsWith("corr-"));
        assertEquals(Instant.parse("2026-07-05T10:30:00Z"), envelope.occurredAt());
        assertTrue(envelope.payload().toString().contains("ord-test-2"));

        Map<?, ?> serialized = JsonMapper.builderWithJackson2Defaults().build().readValue(
            JsonMapper.builderWithJackson2Defaults().build().writeValueAsString(envelope),
            Map.class
        );
        assertEquals(
            List.of("eventId", "eventType", "occurredAt", "correlationId", "causationId", "producer", "schemaVersion", "payload"),
            new ArrayList<>(serialized.keySet())
        );
        assertFalse(serialized.containsKey("sourceCommandId"));
        assertFalse(serialized.containsKey("attributes"));
    }

    @Test
    void subscriberHandlerDeduplicatesDuplicateEventId() {
        RecordingConsumedEventLog log = new RecordingConsumedEventLog();
        PostSalesEventHandler handler = new PostSalesEventHandler(log);
        EventEnvelope envelope = new EventEnvelope(
            "evt-duplicate",
            "JourneyOrderCancelled",
            Instant.parse("2026-07-05T10:30:00Z"),
            "corr-duplicate",
            "evt-source",
            "journey-order",
            1,
            Map.of("journeyOrderId", "ord-test-3")
        );

        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(1, log.recorded.size());
    }

    @Test
    void fakeSubscriberUsesDeliveryCountForDlqDecision() {
        FakeDeliveryCounterSubscriber subscriber = new FakeDeliveryCounterSubscriber();
        EventEnvelope envelope = new EventEnvelope(
            "evt-retry",
            "JourneyOrderCancelled",
            Instant.parse("2026-07-05T10:30:00Z"),
            "corr-retry",
            "evt-source",
            "journey-order",
            1,
            Map.of("journeyOrderId", "ord-test-4")
        );

        for (int attempt = 1; attempt < 5; attempt++) {
            subscriber.deliver(envelope, attempt, ignored -> EventSubscriber.HandlerResult.TRANSIENT_FAILURE);
        }
        subscriber.deliver(envelope, 5, ignored -> EventSubscriber.HandlerResult.TRANSIENT_FAILURE);

        assertEquals(1, subscriber.dlq.size());
        assertEquals("evt-retry", subscriber.dlq.getFirst().eventId());
    }

    private static final class FakeDeliveryCounterSubscriber {
        private final List<EventEnvelope> dlq = new ArrayList<>();

        void deliver(EventEnvelope envelope, int deliveryCount, EventSubscriber.EventHandler handler) {
            EventSubscriber.HandlerResult result = deliveryCount >= 5
                ? EventSubscriber.HandlerResult.FATAL_FAILURE
                : handler.handle(envelope);
            if (result == EventSubscriber.HandlerResult.FATAL_FAILURE) {
                dlq.add(envelope);
            }
        }
    }

    private static final class RecordingConsumedEventLog implements ConsumedEventLog {
        private final List<String> recorded = new ArrayList<>();

        @Override
        public boolean recordIfFirstSeen(String eventId) {
            if (recorded.contains(eventId)) {
                return false;
            }
            recorded.add(eventId);
            return true;
        }
    }
}
