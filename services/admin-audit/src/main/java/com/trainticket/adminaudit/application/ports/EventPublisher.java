package com.trainticket.adminaudit.application.ports;

public interface EventPublisher {
    void publish(EventEnvelope envelope) throws PublishFailedException;
}
