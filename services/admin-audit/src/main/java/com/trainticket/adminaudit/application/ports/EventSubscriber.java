package com.trainticket.adminaudit.application.ports;

import com.trainticket.platformkit.messaging.EventEnvelope;
import java.util.List;

public interface EventSubscriber {
    void subscribe(List<String> streams, String group, String consumerName, EventHandler handler)
        throws SubscribeFailedException;

    @FunctionalInterface
    interface EventHandler {
        HandlerResult handle(EventEnvelope envelope);
    }

    enum HandlerResult {
        SUCCESS,
        TRANSIENT_FAILURE,
        FATAL_FAILURE
    }
}
