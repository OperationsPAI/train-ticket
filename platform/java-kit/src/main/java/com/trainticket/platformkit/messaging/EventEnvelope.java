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
        eventId = requirePrefixedUuidV7(eventId, "eventId", "evt-");
        eventType = requireText(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        correlationId = requirePrefixedUuidV7(correlationId, "correlationId", "corr-");
        if (causationId != null) causationId = requirePrefixedUuidV7(causationId, "causationId", "cmd-", "evt-");
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
        return value == null ? UuidV7.correlationId() : requirePrefixedUuidV7(value, "correlationId", "corr-");
    }

    private static String canonicalCausationId(String value) {
        return value == null ? UuidV7.commandId() : requirePrefixedUuidV7(value, "causationId", "cmd-", "evt-");
    }

    static String requirePrefixedUuidV7(String value, String name, String... prefixes) {
        String text = requireText(value, name);
        for (String prefix : prefixes) {
            if (text.startsWith(prefix) && UuidV7.isUuidV7(text.substring(prefix.length()))) return text;
        }
        throw new IllegalArgumentException(name + " must be " + String.join(" or ", prefixes) + " followed by a UUID v7");
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
