package com.trainticket.platformkit.messaging;

import java.util.List;
import java.util.function.Function;

public interface EventSubscriber extends AutoCloseable {
    void subscribe(List<String> streams, String group, String consumerName, Function<EventEnvelope, HandlerResult> handler) throws SubscribeFailed;
    void shutdown();
    @Override default void close() { shutdown(); }
    final class SubscribeFailed extends RuntimeException { public SubscribeFailed(String message, Throwable cause) { super(message, cause); } }
}
