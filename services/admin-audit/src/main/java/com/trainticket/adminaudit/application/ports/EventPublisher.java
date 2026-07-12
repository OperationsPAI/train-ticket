package com.trainticket.adminaudit.application.ports;

import com.trainticket.platformkit.messaging.EventEnvelope;
public interface EventPublisher {
    void publish(EventEnvelope envelope) throws PublishFailedException;
}
