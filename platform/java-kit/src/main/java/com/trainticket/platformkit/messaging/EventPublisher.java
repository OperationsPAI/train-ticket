package com.trainticket.platformkit.messaging;

public interface EventPublisher {
    void publish(EventEnvelope envelope) throws PublishFailed;
    final class PublishFailed extends RuntimeException { public PublishFailed(String message, Throwable cause) { super(message, cause); } }
}
