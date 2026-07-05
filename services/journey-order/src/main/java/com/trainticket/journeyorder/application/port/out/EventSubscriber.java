package com.trainticket.journeyorder.application.port.out;

import com.trainticket.journeyorder.domain.EventEnvelope;
import java.util.List;
import java.util.function.Function;

/**
 * Abstract port for subscribing to event streams as a consumer group member.
 * Domain and application layers depend ONLY on this port — never on Redis types.
 */
public interface EventSubscriber {
    /**
     * Subscribe to one or more event streams as a consumer group member.
     *
     * @param streams      List of stream keys to subscribe to
     * @param group        Consumer group name (consuming context name)
     * @param consumerName Unique consumer instance identifier
     * @param handler      Callback receiving each EventEnvelope; returns a
     *                     HandlerResult indicating success or failure
     * @throws SubscribeFailed if the subscriber could not start
     */
    void subscribe(List<String> streams, String group, String consumerName,
                   Function<EventEnvelope, HandlerResult> handler) throws SubscribeFailed;

    /** Gracefully stop the subscriber polling loop. */
    void shutdown();

    /** Result returned by the handler callback. */
    sealed interface HandlerResult permits Success, TransientError, FatalError {}

    /** Processing succeeded. */
    record Success() implements HandlerResult {}

    /** Transient failure — do NOT ack; leave in PEL for retry. */
    record TransientError(String reason) implements HandlerResult {}

    /** Fatal failure — move to DLQ and ack. */
    record FatalError(String reason) implements HandlerResult {}

    /** Error thrown when subscription cannot be started. */
    final class SubscribeFailed extends RuntimeException {
        public SubscribeFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
