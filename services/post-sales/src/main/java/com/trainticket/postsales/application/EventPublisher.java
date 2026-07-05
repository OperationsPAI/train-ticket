package com.trainticket.postsales.application;

public interface EventPublisher {
    void publish(EventEnvelope envelope) throws PublishFailedException;
}
