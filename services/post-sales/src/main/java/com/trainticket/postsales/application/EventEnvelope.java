package com.trainticket.postsales.application;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record EventEnvelope(
    String eventId,
    String eventType,
    int schemaVersion,
    String producer,
    String sourceCommandId,
    String causationId,
    String correlationId,
    Instant occurredAt,
    Map<String, String> attributes,
    Object payload
) {
    public EventEnvelope {
        eventId = requirePrefixed(eventId, "eventId", "evt-");
        eventType = requireText(eventType, "eventType");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        producer = requireText(producer, "producer");
        sourceCommandId = requirePrefixed(sourceCommandId, "sourceCommandId", "cmd-");
        causationId = requirePrefixed(causationId, "causationId", "cmd-", "evt-");
        correlationId = requirePrefixed(correlationId, "correlationId", "corr-");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes are required"));
        Objects.requireNonNull(payload, "payload is required");
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requirePrefixed(String value, String name, String... prefixes) {
        String text = requireText(value, name);
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return text;
            }
        }
        throw new IllegalArgumentException(name + " must start with " + String.join(" or ", prefixes));
    }
}
