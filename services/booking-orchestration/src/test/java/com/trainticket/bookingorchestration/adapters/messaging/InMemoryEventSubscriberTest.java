package com.trainticket.bookingorchestration.adapters.messaging;

import static org.junit.jupiter.api.Assertions.*;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.bookingorchestration.application.HandlerResult;
import com.trainticket.bookingorchestration.application.SubscriberConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryEventSubscriberTest {

    private InMemoryEventSubscriber subscriber;
    private AtomicInteger handlerCallCount;

    @BeforeEach
    void setUp() {
        subscriber = new InMemoryEventSubscriber();
        handlerCallCount = new AtomicInteger(0);
    }

    @Test
    void deduplicatesDuplicateEventId() {
        Function<EventEnvelope, HandlerResult> handler = envelope -> {
            handlerCallCount.incrementAndGet();
            return new HandlerResult.Success();
        };

        SubscriberConfig config = new SubscriberConfig(
            List.of("events:test"), "test-group", "test-consumer", handler);
        subscriber.subscribe(config);

        String eventId = "evt-" + UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(
            eventId, "TestEvent", 1, "booking-orchestration",
            "cmd-" + UUID.randomUUID(), "corr-" + UUID.randomUUID(),
            Instant.now(), "payload");

        // First delivery — should be processed
        HandlerResult first = subscriber.receive(envelope);
        assertInstanceOf(HandlerResult.Success.class, first);
        assertEquals(1, handlerCallCount.get());
        assertTrue(subscriber.hasConsumed(eventId));

        // Second delivery with same eventId — should be deduplicated
        HandlerResult second = subscriber.receive(envelope);
        assertNull(second);
        assertEquals(1, handlerCallCount.get(), "handler must not be called again for duplicate eventId");
    }

    @Test
    void processesEventsWithDifferentIds() {
        Function<EventEnvelope, HandlerResult> handler = envelope -> {
            handlerCallCount.incrementAndGet();
            return new HandlerResult.Success();
        };

        SubscriberConfig config = new SubscriberConfig(
            List.of("events:test"), "test-group", "test-consumer", handler);
        subscriber.subscribe(config);

        EventEnvelope first = new EventEnvelope(
            "evt-" + UUID.randomUUID(), "TestEvent", 1, "booking-orchestration",
            null, null, Instant.now(), "a");
        EventEnvelope second = new EventEnvelope(
            "evt-" + UUID.randomUUID(), "TestEvent", 1, "booking-orchestration",
            null, null, Instant.now(), "b");

        assertNotNull(subscriber.receive(first));
        assertNotNull(subscriber.receive(second));
        assertEquals(2, handlerCallCount.get());
    }

    @Test
    void doesNotRecordOnTransientError() {
        Function<EventEnvelope, HandlerResult> handler = envelope -> {
            handlerCallCount.incrementAndGet();
            return new HandlerResult.TransientError("timeout");
        };

        SubscriberConfig config = new SubscriberConfig(
            List.of("events:test"), "test-group", "test-consumer", handler);
        subscriber.subscribe(config);

        String eventId = "evt-" + UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(
            eventId, "TestEvent", 1, "booking-orchestration",
            null, null, Instant.now(), "payload");

        // Transient error — should not mark as consumed
        HandlerResult result = subscriber.receive(envelope);
        assertInstanceOf(HandlerResult.TransientError.class, result);
        assertFalse(subscriber.hasConsumed(eventId));

        // Retry — handler called again
        result = subscriber.receive(envelope);
        assertInstanceOf(HandlerResult.TransientError.class, result);
        assertEquals(2, handlerCallCount.get());
    }
}
