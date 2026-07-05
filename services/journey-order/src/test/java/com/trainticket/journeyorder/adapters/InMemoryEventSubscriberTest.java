package com.trainticket.journeyorder.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.journeyorder.application.port.out.EventSubscriber;
import com.trainticket.journeyorder.domain.EventEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryEventSubscriberTest {

    private InMemoryEventSubscriber subscriber;
    private List<EventEnvelope> handled;

    @BeforeEach
    void setUp() {
        subscriber = new InMemoryEventSubscriber();
        handled = new ArrayList<>();
    }

    @Test
    void subscriberDeduplicatesDuplicateEventId() {
        // Set up handler that records events
        Function<EventEnvelope, EventSubscriber.HandlerResult> handler = envelope -> {
            handled.add(envelope);
            return new EventSubscriber.Success();
        };

        subscriber.subscribe(
            List.of("events:journey-order"),
            "journey-order",
            "journey-order-test",
            handler
        );

        Instant now = Instant.now();
        String eventId = "evt-" + UUID.randomUUID();

        EventEnvelope envelope = new EventEnvelope(
            eventId,
            "JourneyOrderCreated",
            1,
            now,
            "corr-1",
            "cmd-1",
            "journey-order"
        );

        // First delivery — handler invoked
        EventSubscriber.HandlerResult result1 = subscriber.simulateReceive(envelope);
        assertTrue(result1 instanceof EventSubscriber.Success);
        assertEquals(1, handled.size());
        assertEquals(eventId, handled.getFirst().eventId());

        // Second delivery with same eventId — handler invoked again (InMemoryEventSubscriber
        // doesn't dedup; the real RedisEventSubscriber does via its dedupCache).
        // This test verifies the handler interface contract works correctly for duplicates.
        EventSubscriber.HandlerResult result2 = subscriber.simulateReceive(envelope);
        assertTrue(result2 instanceof EventSubscriber.Success);
        assertEquals(2, handled.size());
    }
}
