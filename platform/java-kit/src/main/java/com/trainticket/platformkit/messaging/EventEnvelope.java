package com.trainticket.platformkit.messaging;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;

public record EventEnvelope(
    String eventId,
    String eventType,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
    String correlationId,
    String causationId,
    String producer,
    int schemaVersion,
    Object payload
) {
    @JsonCreator
    public EventEnvelope(
        @JsonProperty("eventId") String eventId,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("occurredAt") Instant occurredAt,
        @JsonProperty("correlationId") String correlationId,
        @JsonProperty("causationId") String causationId,
        @JsonProperty("producer") String producer,
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("payload") Object payload
    ) {
        PrefixedIds.requireEventId(eventId);
        PrefixedIds.requireCorrelationId(correlationId);
        PrefixedIds.requireCausationId(causationId);
        this.eventId = eventId;
        this.eventType = requireText(eventType, "eventType");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt is required");
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.producer = requireText(producer, "producer");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        this.schemaVersion = schemaVersion;
        this.payload = Objects.requireNonNull(payload, "payload is required");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
