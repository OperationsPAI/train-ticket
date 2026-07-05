package com.trainticket.financesettlement.adapters.messaging;

import com.trainticket.financesettlement.application.EventEnvelope;
import com.trainticket.financesettlement.application.EventPublisher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "finance.messaging.redis.enabled", havingValue = "false", matchIfMissing = false)
public class InMemoryEventPublisher implements EventPublisher {
    private final List<EventEnvelope> published = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void publish(EventEnvelope envelope) {
        published.add(envelope);
    }

    public List<EventEnvelope> published() {
        synchronized (published) {
            return List.copyOf(published);
        }
    }
}
