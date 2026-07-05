package com.trainticket.platformkit.messaging;

public interface EventPublisher {
    void publish(EventEnvelope envelope) throws PublishFailedException;
}
