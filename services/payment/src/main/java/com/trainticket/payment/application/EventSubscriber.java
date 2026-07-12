package com.trainticket.payment.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import java.util.List;

public interface EventSubscriber {
    void subscribe(List<String> streams, String group, String consumerName, EventHandler handler) throws SubscribeFailedException;

    @FunctionalInterface
    interface EventHandler {
        HandlerResult handle(EventEnvelope envelope);
    }
}
