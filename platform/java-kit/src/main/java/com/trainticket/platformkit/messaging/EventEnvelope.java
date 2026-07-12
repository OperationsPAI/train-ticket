package com.trainticket.platformkit.messaging;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.trainticket.platformkit.observability.EventTraceContext;
import java.time.Instant;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
    String eventId,
    String eventType,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
    String correlationId,
    @JsonInclude(JsonInclude.Include.NON_NULL) String causationId,
    String producer,
    int schemaVersion,
    Object payload,
    @JsonInclude(JsonInclude.Include.NON_NULL) String traceparent,
    @JsonInclude(JsonInclude.Include.NON_NULL) String tracestate
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
        @JsonProperty("payload") Object payload,
        @JsonProperty("traceparent") String traceparent,
        @JsonProperty("tracestate") String tracestate
    ) {
        PrefixedIds.requireEventId(eventId);
        PrefixedIds.requireCorrelationId(correlationId);
        if (causationId != null) {
            PrefixedIds.requireCausationId(causationId);
        }
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
        this.traceparent = blankToNull(traceparent);
        this.tracestate = this.traceparent == null ? null : blankToNull(tracestate);
    }

    public EventEnvelope(
        String eventId,
        String eventType,
        Instant occurredAt,
        String correlationId,
        String causationId,
        String producer,
        int schemaVersion,
        Object payload
    ) {
        this(eventId, eventType, occurredAt, correlationId, causationId, producer, schemaVersion, payload, currentTraceContext());
    }

    private EventEnvelope(
        String eventId,
        String eventType,
        Instant occurredAt,
        String correlationId,
        String causationId,
        String producer,
        int schemaVersion,
        Object payload,
        EventTraceContext traceContext
    ) {
        this(eventId, eventType, occurredAt, correlationId, causationId, producer, schemaVersion, payload, traceContext.traceparent(), traceContext.tracestate());
    }

    public EventEnvelope withTraceContext(String traceparent, String tracestate) {
        return new EventEnvelope(eventId, eventType, occurredAt, correlationId, causationId, producer, schemaVersion, payload, traceparent, tracestate);
    }

    private static EventTraceContext currentTraceContext() {
        return EventTraceContext.current();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
