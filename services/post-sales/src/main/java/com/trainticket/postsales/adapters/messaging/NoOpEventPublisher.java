package com.trainticket.postsales.adapters.messaging;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.postsales.application.EventPublisher;
import org.springframework.stereotype.Component;

@Component
public class NoOpEventPublisher implements EventPublisher {
    @Override
    public void publish(EventEnvelope envelope) {
    }
}
