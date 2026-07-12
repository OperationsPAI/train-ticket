package com.trainticket.journeyorder.application.port.out;

import com.trainticket.platformkit.messaging.EventEnvelope;

/**
 * Abstract port for publishing domain events to the event bus.
 * Domain and application layers depend ONLY on this port — never on Redis types.
 */
public interface EventPublisher {
    /**
     * Publish a fully-populated EventEnvelope to the event bus.
     *
     * @param envelope the event envelope to publish
     * @throws PublishFailed if the event could not be published after retries
     */
    void publish(EventEnvelope envelope) throws PublishFailed;

    /** Error thrown when event publishing fails persistently. */
    final class PublishFailed extends RuntimeException {
        public PublishFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
