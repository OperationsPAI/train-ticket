package com.trainticket.adminaudit.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.adminaudit.application.ports.EventPublisher;
import com.trainticket.adminaudit.application.ports.PublishFailedException;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.persistence.OutboxAppender;
import java.util.Objects;

public class TransactionalOutboxEventPublisher implements EventPublisher {
    private final OutboxAppender outboxAppender;
    private final ObjectMapper objectMapper;

    public TransactionalOutboxEventPublisher(OutboxAppender outboxAppender, ObjectMapper objectMapper) {
        this.outboxAppender = Objects.requireNonNull(outboxAppender, "outboxAppender is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    @Override
    public void publish(EventEnvelope envelope) {
        try {
            outboxAppender.append(envelope, objectMapper.writeValueAsString(envelope));
        } catch (JsonProcessingException exception) {
            throw new PublishFailedException("event envelope could not be serialized", exception);
        }
    }
}
