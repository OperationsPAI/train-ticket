package com.trainticket.travelerprofile.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessagingPortTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void publisherReceivesCorrectEnvelopeShape() {
        InMemoryPublisher publisher = new InMemoryPublisher();
        EventEnvelope envelope = new EventEnvelope(
            "evt-018f0000-0000-7000-8000-000000000010",
            "TravelerProfileUpdated",
            Instant.parse("2026-07-05T10:30:00Z"),
            "corr-018f0000-0000-7000-8000-000000000011",
            "cmd-018f0000-0000-7000-8000-000000000012",
            "traveler-profile",
            1,
            objectMapper.createObjectNode().put("travelerId", "tvl-018f0000-0000-7000-8000-000000000013")
        );

        publisher.publish(envelope);

        EventEnvelope published = publisher.envelopes.getFirst();
        assertTrue(published.eventId().startsWith("evt-"));
        assertEquals("TravelerProfileUpdated", published.eventType());
        assertEquals("traveler-profile", published.producer());
        assertEquals(1, published.schemaVersion());
        assertEquals("tvl-018f0000-0000-7000-8000-000000000013", published.payload().get("travelerId").asText());
    }

    @Test
    void subscriberDeduplicatesDuplicateEventId() {
        ConsumedEventLog log = new ConsumedEventLog();
        List<String> handled = new ArrayList<>();
        DeduplicatingEventHandler handler = new DeduplicatingEventHandler(log, envelope -> {
            handled.add(envelope.eventId());
            return EventSubscriber.HandlerResult.SUCCESS;
        });
        EventEnvelope envelope = new EventEnvelope(
            "evt-duplicate",
            "TravelerProfileUpdated",
            Instant.parse("2026-07-05T10:30:00Z"),
            "corr-1",
            "cmd-1",
            "traveler-profile",
            1,
            objectMapper.createObjectNode()
        );

        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));

        assertEquals(List.of("evt-duplicate"), handled);
        assertTrue(log.hasConsumed("evt-duplicate"));
    }

    @Test
    void transientFailureIsNotRecordedAndRedeliveryInvokesDelegateAgain() {
        ConsumedEventLog log = new ConsumedEventLog();
        List<String> handled = new ArrayList<>();
        DeduplicatingEventHandler handler = new DeduplicatingEventHandler(log, envelope -> {
            handled.add(envelope.eventId());
            return handled.size() == 1
                ? EventSubscriber.HandlerResult.TRANSIENT_FAILURE
                : EventSubscriber.HandlerResult.SUCCESS;
        });
        EventEnvelope envelope = new EventEnvelope(
            "evt-redelivered",
            "TravelerProfileUpdated",
            Instant.parse("2026-07-05T10:30:00Z"),
            "corr-1",
            "cmd-1",
            "traveler-profile",
            1,
            objectMapper.createObjectNode()
        );

        assertEquals(EventSubscriber.HandlerResult.TRANSIENT_FAILURE, handler.handle(envelope));
        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));

        assertEquals(List.of("evt-redelivered", "evt-redelivered"), handled);
        assertTrue(log.hasConsumed("evt-redelivered"));
    }

    private static final class InMemoryPublisher implements EventPublisher {
        private final List<EventEnvelope> envelopes = new ArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            envelopes.add(envelope);
        }
    }
}
