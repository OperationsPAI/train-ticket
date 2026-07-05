package com.trainticket.journeyorder.adapters;

import com.trainticket.journeyorder.application.port.out.EventPublisher;
import com.trainticket.journeyorder.domain.EventEnvelope;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory fake implementation of EventPublisher for unit tests.
 * Does NOT use Redis — tests run without a live Redis.
 */
public class InMemoryEventPublisher implements EventPublisher {

    private final List<EventEnvelope> published = new ArrayList<>();
    private boolean failOnPublish = false;

    @Override
    public void publish(EventEnvelope envelope) throws PublishFailed {
        if (failOnPublish) {
            throw new PublishFailed("Simulated publish failure", null);
        }
        published.add(envelope);
    }

    public List<EventEnvelope> published() {
        return List.copyOf(published);
    }

    public void setFailOnPublish(boolean fail) {
        this.failOnPublish = fail;
    }

    public void clear() {
        published.clear();
    }
}
