package com.trainticket.payment.application;

public interface EventPublisher {
    void publish(EventEnvelope envelope) throws PublishFailedException;
}
