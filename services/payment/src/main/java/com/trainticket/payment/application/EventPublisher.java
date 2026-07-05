package com.trainticket.payment.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
public interface EventPublisher {
    void publish(EventEnvelope envelope) throws PublishFailedException;
}
