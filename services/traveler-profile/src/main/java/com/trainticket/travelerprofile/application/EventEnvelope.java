package com.trainticket.travelerprofile.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record EventEnvelope(
    String eventId,
    String eventType,
    Instant occurredAt,
    String correlationId,
    String causationId,
    String producer,
    int schemaVersion,
    JsonNode payload
) {
}
