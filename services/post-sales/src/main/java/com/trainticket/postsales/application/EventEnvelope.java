package com.trainticket.postsales.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
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
        eventId = requirePrefixed(eventId, "eventId", "evt-");
        eventType = requireText(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        correlationId = requirePrefixed(correlationId, "correlationId", "corr-");
        if (causationId != null) {
            causationId = requirePrefixed(causationId, "causationId", "cmd-", "evt-");
        }
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
