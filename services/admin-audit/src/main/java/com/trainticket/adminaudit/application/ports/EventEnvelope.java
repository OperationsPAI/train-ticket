package com.trainticket.adminaudit.application.ports;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;

@JsonPropertyOrder({
    "eventId",
    "eventType",
    "occurredAt",
    "correlationId",
    "causationId",
    "producer",
    "schemaVersion",
    "payload"
})
public record EventEnvelope(
    String eventId,
    String eventType,
    Instant occurredAt,
    String correlationId,
    String causationId,
    String producer,
    int schemaVersion,
    Object payload
) {}
