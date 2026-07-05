package com.trainticket.travelerprofile.adapters.messaging;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.travelerprofile.application.EventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@ConditionalOnProperty(name = "traveler-profile.redis.enabled", havingValue = "false")
public class NoOpEventPublisher implements EventPublisher {
    @Override
    public void publish(EventEnvelope envelope) {
    }
}
