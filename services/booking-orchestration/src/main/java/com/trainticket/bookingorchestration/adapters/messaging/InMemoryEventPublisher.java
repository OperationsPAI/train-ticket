package com.trainticket.bookingorchestration.adapters.messaging;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.bookingorchestration.application.EventPublisher;
import java.util.ArrayList;
import java.util.List;

public class InMemoryEventPublisher implements EventPublisher {
    private final List<EventEnvelope> published = new ArrayList<>();

    @Override
    public void publish(EventEnvelope envelope) {
        published.add(envelope);
    }

    public List<EventEnvelope> getPublished() {
        return List.copyOf(published);
    }

    public void clear() {
        published.clear();
    }
}
