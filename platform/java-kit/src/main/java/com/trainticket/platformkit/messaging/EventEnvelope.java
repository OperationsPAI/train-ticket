package com.trainticket.platformkit.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"eventId", "eventType", "occurredAt", "correlationId", "causationId", "producer", "schemaVersion", "payload"})
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
        if (causationId != null) causationId = requirePrefixed(causationId, "causationId", "cmd-", "evt-");
        producer = requireText(producer, "producer");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        payload = payload == null ? Map.of() : payload;
    }

    public EventEnvelope(String eventId, String eventType, int schemaVersion, Instant occurredAt, String correlationId, String causationId, String producer) {
        this(eventId, eventType, occurredAt, correlationId, causationId, producer, schemaVersion, Map.of());
    }

    public EventEnvelope(String eventId, String eventType, int schemaVersion, Instant occurredAt, String correlationId, String causationId, String producer, Map<String, Object> payload) {
        this(eventId, eventType, occurredAt, correlationId, causationId, producer, schemaVersion, payload == null ? Map.of() : Map.copyOf(payload));
    }

    public static EventEnvelope create(String eventType, Instant occurredAt, String sourceCommandId, String correlationId, String producer) {
        return new EventEnvelope(UuidV7.eventId(), eventType, occurredAt, canonicalCorrelationId(correlationId), canonicalCausationId(sourceCommandId), producer, 1, Map.of());
    }

    public EventEnvelope withPayload(Map<String, Object> payload) {
        return new EventEnvelope(eventId, eventType, occurredAt, correlationId, causationId, producer, schemaVersion, payload == null ? Map.of() : Map.copyOf(payload));
    }

    private static String canonicalCorrelationId(String value) {
        if (value != null && value.startsWith("corr-")) return value;
        return UuidV7.correlationId();
    }

    private static String canonicalCausationId(String value) {
        if (value != null && (value.startsWith("cmd-") || value.startsWith("evt-"))) return value;
        return UuidV7.commandId();
    }

    private static String requirePrefixed(String value, String name, String... prefixes) {
        String text = requireText(value, name);
        for (String prefix : prefixes) if (text.startsWith(prefix)) return text;
        throw new IllegalArgumentException(name + " must start with " + String.join(" or ", prefixes));
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
