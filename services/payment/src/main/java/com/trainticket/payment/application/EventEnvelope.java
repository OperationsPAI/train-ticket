package com.trainticket.payment.application;

import java.time.Instant;
import java.util.Objects;

public record EventEnvelope(
    String eventId,
    String eventType,
    Instant occurredAt,
    String correlationId,
    String causationId,
    String producer,
    int schemaVersion,
    Object payload
) {
    public EventEnvelope {
        eventId = requireText(eventId, "eventId");
        eventType = requireText(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        correlationId = requireText(correlationId, "correlationId");
        causationId = requireText(causationId, "causationId");
        producer = requireText(producer, "producer");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(payload, "payload is required");
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
