package com.trainticket.journeyorder.adapters;

import com.trainticket.platformkit.messaging.InMemoryEventSubscriber;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.journeyorder.application.port.out.EventSubscriber;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0b001",
            "JourneyOrderCreated",
            Instant.now(),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0b002",
            "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0b003",
            "journey-order",
            1,
            Map.of()
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
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0b004",
            "PaymentCaptured",
            Instant.now(),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0b005",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0b006",
            "payment",
            1,
            Map.of()
        );

        for (int i = 0; i < 4; i++) {
            assertTrue(subscriber.simulateReceive(envelope) instanceof EventSubscriber.TransientError);
        }
        assertTrue(subscriber.simulateReceive(envelope) instanceof EventSubscriber.FatalError);
        assertEquals(1, subscriber.dlq().size());
    }
}
