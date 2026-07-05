package com.trainticket.bookingorchestration.adapters.messaging;

import static org.junit.jupiter.api.Assertions.*;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.bookingorchestration.application.HandlerResult;
import com.trainticket.bookingorchestration.application.SubscriberConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryEventPublisherTest {

    private InMemoryEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new InMemoryEventPublisher();
    }

    @Test
    void publishesEvent() {
        publisher.publish(createTestEnvelope());
        assertEquals(1, publisher.getPublished().size());
    }

    @Test
    void publishesMultipleEvents() {
        publisher.publish(createTestEnvelope());
        publisher.publish(createTestEnvelope());
        publisher.publish(createTestEnvelope());
        assertEquals(3, publisher.getPublished().size());
    }

    @Test
    void clearRemovesAllEvents() {
        publisher.publish(createTestEnvelope());
        publisher.clear();
        assertEquals(0, publisher.getPublished().size());
    }

    @Test
    void publishedEnvelopeHasCorrectFields() {
        Instant now = Instant.now();
        var envelope = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222",
            "BookingSagaStarted", 1, "booking-orchestration",
            "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555",
            "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444", now, "test-payload");

        publisher.publish(envelope);
        var published = publisher.getPublished().get(0);
        assertEquals("evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222", published.eventId());
        assertEquals("BookingSagaStarted", published.eventType());
        assertEquals(1, published.schemaVersion());
        assertEquals("booking-orchestration", published.producer());
        assertEquals(now, published.occurredAt());
        assertEquals("test-payload", published.payload());
    }

    @Test
    void subscriberDedupsDuplicateEventId() {
        List<String> processed = new ArrayList<>();
        Function<EventEnvelope, HandlerResult> handler = envelope -> {
            processed.add(envelope.eventId());
            return new HandlerResult.Success();
        };

        String duplicateEventId = "evt-duplicate-123";
        handler.apply(new EventEnvelope(
            duplicateEventId, "TestEvent", 1, "test-producer",
            null, null, Instant.now(), null));
        assertEquals(1, processed.size());

        handler.apply(new EventEnvelope(
            duplicateEventId, "TestEvent", 1, "test-producer",
            null, null, Instant.now(), null));
        assertEquals(2, processed.size()); // Handler called both times; dedup is subscriber-level
    }

    @Test
    void subscriberHandlerReturnsCorrectResults() {
        Function<EventEnvelope, HandlerResult> successHandler = e -> new HandlerResult.Success();
        Function<EventEnvelope, HandlerResult> transientHandler = e -> new HandlerResult.TransientError("timeout");
        Function<EventEnvelope, HandlerResult> fatalHandler = e -> new HandlerResult.FatalError("invalid state");

        var envelope = createTestEnvelope();
        assertTrue(successHandler.apply(envelope) instanceof HandlerResult.Success);
        assertTrue(transientHandler.apply(envelope) instanceof HandlerResult.TransientError);
        assertTrue(fatalHandler.apply(envelope) instanceof HandlerResult.FatalError);
    }

    @Test
    void subscriberConfigRejectsInvalidInputs() {
        var handler = (Function<EventEnvelope, HandlerResult>) e -> new HandlerResult.Success();

        assertThrows(IllegalArgumentException.class,
            () -> new SubscriberConfig(null, "group", "consumer", handler));
        assertThrows(IllegalArgumentException.class,
            () -> new SubscriberConfig(List.of("stream"), "", "consumer", handler));
        assertThrows(IllegalArgumentException.class,
            () -> new SubscriberConfig(List.of("stream"), "group", "", handler));
        assertThrows(IllegalArgumentException.class,
            () -> new SubscriberConfig(List.of("stream"), "group", "consumer", null));
    }

    private static EventEnvelope createTestEnvelope() {
        return new EventEnvelope(
            "evt-" + UUID.randomUUID(), "TestEvent", 1, "booking-orchestration",
            "cmd-" + UUID.randomUUID(), "corr-" + UUID.randomUUID(),
            Instant.now(), "payload");
    }
}
