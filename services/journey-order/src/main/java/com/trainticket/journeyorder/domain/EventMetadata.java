package com.trainticket.journeyorder.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EventMetadata(
    String eventId,
    Instant occurredAt,
    String sourceCommandId,
    String causationId,
    String correlationId,
    int schemaVersion,
    Map<String, String> attributes
) {
    public EventMetadata {
        eventId = requireText(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        sourceCommandId = requireText(sourceCommandId, "sourceCommandId");
        causationId = requireText(causationId, "causationId");
        correlationId = requireText(correlationId, "correlationId");
        if (schemaVersion < 1) {
            throw new DomainRuleViolation("schemaVersion must be positive");
        }
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes are required"));
    }

    static EventMetadata create(Instant occurredAt, String sourceCommandId, String causationId, String correlationId, Map<String, String> attributes) {
        return new EventMetadata(UUID.randomUUID().toString(), occurredAt, sourceCommandId, causationId, correlationId, 1, attributes);
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
