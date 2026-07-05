package com.trainticket.bookingorchestration.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
/**
 * Port: EventPublisher — broker-independent interface for publishing domain events.
 */
@FunctionalInterface
public interface EventPublisher {
    /**
     * Publish a domain event to the event bus.
     *
     * @param envelope the fully-populated EventEnvelope
     * @throws PublishFailed if the event could not be published after retries
     */
    void publish(EventEnvelope envelope);
}
