package com.trainticket.bookingorchestration.adapters.messaging;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.bookingorchestration.application.EventSubscriber;
import com.trainticket.bookingorchestration.application.HandlerResult;
import com.trainticket.bookingorchestration.application.SubscriberConfig;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory EventSubscriber for testing — no Redis required.
 * Implements the same dedup-by-eventId logic as RedisStreamsEventSubscriber
 * so that dedup behaviour can be verified in unit tests.
 */
public class InMemoryEventSubscriber implements EventSubscriber {

    private final Set<String> consumedEventIds = ConcurrentHashMap.newKeySet();
    private volatile SubscriberConfig currentConfig;

    @Override
    public void subscribe(SubscriberConfig config) {
        this.currentConfig = config;
    }

    /**
     * Simulate receiving and processing a single event, including dedup.
     * Returns the HandlerResult, or null if the event was deduplicated.
     */
    public HandlerResult receive(EventEnvelope envelope) {
        if (consumedEventIds.contains(envelope.eventId())) {
            return null; // deduplicated
        }
        HandlerResult result = currentConfig.handler().apply(envelope);
        if (result instanceof HandlerResult.Success) {
            consumedEventIds.add(envelope.eventId());
        }
        return result;
    }

    public boolean hasConsumed(String eventId) {
        return consumedEventIds.contains(eventId);
    }

    public void reset() {
        consumedEventIds.clear();
    }
}
