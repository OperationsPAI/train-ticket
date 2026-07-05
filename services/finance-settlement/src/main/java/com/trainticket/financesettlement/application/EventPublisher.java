package com.trainticket.financesettlement.application;

public interface EventPublisher {
    void publish(EventEnvelope envelope) throws PublishFailedException;
}
