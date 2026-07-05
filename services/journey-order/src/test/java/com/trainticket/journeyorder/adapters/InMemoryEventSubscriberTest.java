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

        EventEnvelope envelope = new EventEnvelope(
            "evt-" + UUID.randomUUID(),
            "JourneyOrderCreated",
            1,
            Instant.now(),
            "corr-" + UUID.randomUUID(),
            "cmd-" + UUID.randomUUID(),
            "journey-order"
        );

        EventSubscriber.HandlerResult result1 = subscriber.simulateReceive(envelope);
        assertTrue(result1 instanceof EventSubscriber.Success);
        assertEquals(1, handled.size());
        assertEquals(envelope.eventId(), handled.getFirst().eventId());

        EventSubscriber.HandlerResult result2 = subscriber.simulateReceive(envelope);
        assertTrue(result2 instanceof EventSubscriber.Success);
        assertEquals(1, handled.size());
    }

    @Test
    void subscriberMovesMessageToDlqAfterFiveDeliveryAttempts() {
        subscriber.subscribe(
            List.of("events:payment"),
            "journey-order",
            "journey-order-test",
            envelope -> new EventSubscriber.TransientError("temporary")
        );

        EventEnvelope envelope = new EventEnvelope(
            "evt-" + UUID.randomUUID(),
            "PaymentCaptured",
            1,
            Instant.now(),
            "corr-" + UUID.randomUUID(),
            "evt-" + UUID.randomUUID(),
            "payment"
        );

        for (int i = 0; i < 4; i++) {
            assertTrue(subscriber.simulateReceive(envelope) instanceof EventSubscriber.TransientError);
        }
        assertTrue(subscriber.simulateReceive(envelope) instanceof EventSubscriber.FatalError);
        assertEquals(1, subscriber.dlq().size());
    }
}
