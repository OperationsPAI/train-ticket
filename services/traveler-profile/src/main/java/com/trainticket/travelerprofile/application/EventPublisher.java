package com.trainticket.travelerprofile.application;

public interface EventPublisher {
    void publish(EventEnvelope envelope) throws PublishFailedException;
}
