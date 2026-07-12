package com.trainticket.platformkit.messaging;

@FunctionalInterface
public interface EventHandler {
    HandlerResult handle(EventEnvelope envelope);
}
