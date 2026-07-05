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
import org.junit.jupiter.api.Test;

class MessagingPortsTest {
    @Test
    void publisherWrapsDomainEventInContractEnvelope() {
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
        assertTrue(envelope.sourceCommandId().startsWith("cmd-"));
        assertTrue(envelope.correlationId().startsWith("corr-"));
        assertEquals(Instant.parse("2026-07-05T10:30:00Z"), envelope.occurredAt());
        assertTrue(envelope.payload().toString().contains("ord-test-2"));
    }

    @Test
    void subscriberHandlerDeduplicatesDuplicateEventId() {
        RecordingConsumedEventLog log = new RecordingConsumedEventLog();
        PostSalesEventHandler handler = new PostSalesEventHandler(log);
        EventEnvelope envelope = new EventEnvelope(
            "evt-duplicate",
            "JourneyOrderCancelled",
            1,
            "journey-order",
            "cmd-cancelled",
            "evt-source",
            "corr-duplicate",
            Instant.parse("2026-07-05T10:30:00Z"),
            java.util.Map.of(),
            java.util.Map.of("journeyOrderId", "ord-test-3")
        );

        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(1, log.recorded.size());
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
