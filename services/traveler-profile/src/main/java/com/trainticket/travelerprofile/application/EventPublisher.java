package com.trainticket.travelerprofile.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
public interface EventPublisher {
    void publish(EventEnvelope envelope) throws PublishFailedException;
}
