package com.trainticket.payment.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import java.util.List;

public class FakeEventSubscriber implements EventSubscriber {
    private EventHandler handler;

    @Override
    public void subscribe(List<String> streams, String group, String consumerName, EventHandler handler) {
        this.handler = handler;
    }

    public HandlerResult emit(EventEnvelope envelope) {
        return handler.handle(envelope);
    }
}
