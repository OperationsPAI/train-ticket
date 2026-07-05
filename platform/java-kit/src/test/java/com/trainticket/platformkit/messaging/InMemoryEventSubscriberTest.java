package com.trainticket.platformkit.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryEventSubscriberTest {
    @Test
    void modelsRetryCountsAndDlqOnFifthAttempt() {
        InMemoryEventSubscriber subscriber = new InMemoryEventSubscriber();
        subscriber.subscribe(List.of("stream"), "group", "consumer", event -> new HandlerResult.TransientError("try again"));
        EventEnvelope envelope = new EventEnvelope(
            UuidV7.eventId(), "TestEvent", Instant.parse("2026-01-01T00:00:00Z"),
            UuidV7.correlationId(), UuidV7.commandId(), "test", 1, Map.of()
        );

        for (int i = 1; i < 5; i++) {
            assertInstanceOf(HandlerResult.TransientError.class, subscriber.receive(envelope));
            assertEquals(i, subscriber.deliveryCount(envelope.eventId()));
        }
        assertInstanceOf(HandlerResult.FatalError.class, subscriber.receive(envelope));
        assertEquals(5, subscriber.deliveryCount(envelope.eventId()));
        assertEquals(List.of(envelope), subscriber.dlq());
    }

    @Test
    void duplicateAfterSuccessReturnsSuccessWithoutRehandling() {
        InMemoryEventSubscriber subscriber = new InMemoryEventSubscriber();
        int[] handled = {0};
        subscriber.subscribe(List.of("stream"), "group", "consumer", event -> {
            handled[0]++;
            return new HandlerResult.Success();
        });
        EventEnvelope envelope = new EventEnvelope(
            UuidV7.eventId(), "TestEvent", Instant.parse("2026-01-01T00:00:00Z"),
            UuidV7.correlationId(), UuidV7.commandId(), "test", 1, Map.of()
        );

        assertInstanceOf(HandlerResult.Success.class, subscriber.receive(envelope));
        assertInstanceOf(HandlerResult.Success.class, subscriber.receive(envelope));
        assertEquals(1, handled[0]);
        assertTrue(subscriber.hasConsumed(envelope.eventId()));
    }
}
