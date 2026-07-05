package com.trainticket.payment.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import java.util.ArrayList;
import java.util.List;

public class FakeEventPublisher implements EventPublisher {
    private final List<EventEnvelope> published = new ArrayList<>();

    @Override
    public void publish(EventEnvelope envelope) {
        published.add(envelope);
    }

    public List<EventEnvelope> published() {
        return List.copyOf(published);
    }
}
