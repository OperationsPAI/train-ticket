package com.trainticket.financesettlement.application;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@JsonPropertyOrder({"eventId", "eventType", "occurredAt", "correlationId", "causationId", "producer", "schemaVersion", "payload"})
public record EventEnvelope(
    String eventId,
    String eventType,
    Instant occurredAt,
    String correlationId,
    String causationId,
    String producer,
    int schemaVersion,
    Map<String, Object> payload
) {
    public EventEnvelope {
        eventId = requirePrefixed(eventId, "evt-", "eventId");
        eventType = requireText(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        correlationId = requirePrefixed(correlationId, "corr-", "correlationId");
        causationId = requireCausationId(causationId);
        producer = requireText(producer, "producer");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload is required"));
    }

    private static String requireCausationId(String value) {
        value = requireText(value, "causationId");
        if (!value.startsWith("cmd-") && !value.startsWith("evt-")) {
            throw new IllegalArgumentException("causationId must start with cmd- or evt-");
        }
        return value;
    }

    private static String requirePrefixed(String value, String prefix, String name) {
        value = requireText(value, name);
        if (!value.startsWith(prefix)) {
            throw new IllegalArgumentException(name + " must start with " + prefix);
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
