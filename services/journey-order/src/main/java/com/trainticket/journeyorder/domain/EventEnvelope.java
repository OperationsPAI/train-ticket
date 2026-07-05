package com.trainticket.journeyorder.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Canonical cross-context event envelope per contract shared-primitives.md.
 * Replaces the previous EventMetadata record.
 */
public record EventEnvelope(
    String eventId,
    String eventType,
    int schemaVersion,
    Instant occurredAt,
    String correlationId,
    String causationId,
    String producer,
    Map<String, Object> payload
) {
    public EventEnvelope(
        String eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String correlationId,
        String causationId,
        String producer
    ) {
        this(eventId, eventType, schemaVersion, occurredAt, correlationId, causationId, producer, Map.of());
    }

    public EventEnvelope {
        eventId = requireText(eventId, "eventId");
        eventType = requireText(eventType, "eventType");
        if (schemaVersion < 1) {
            throw new DomainRuleViolation("schemaVersion must be positive");
        }
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        correlationId = requireText(correlationId, "correlationId");
        causationId = requireText(causationId, "causationId");
        producer = requireText(producer, "producer");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static EventEnvelope create(
        String eventType,
        Instant occurredAt,
        String sourceCommandId,
        String correlationId,
        String producer
    ) {
        return new EventEnvelope(
            "evt-" + UUID.randomUUID(),
            eventType,
            1,
            occurredAt,
            correlationId,
            sourceCommandId != null ? sourceCommandId : correlationId,
            producer,
            Map.of()
        );
    }

    public EventEnvelope withPayload(Map<String, Object> payload) {
        return new EventEnvelope(eventId, eventType, schemaVersion, occurredAt, correlationId, causationId, producer, payload);
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
