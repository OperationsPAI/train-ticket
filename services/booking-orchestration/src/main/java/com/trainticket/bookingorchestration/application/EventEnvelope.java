package com.trainticket.bookingorchestration.application;

import java.time.Instant;

/**
 * Canonical event envelope per docs/08-contracts/messaging.md and shared-primitives.md §1.
 * Wraps every domain event before publishing to the event bus.
 */
public record EventEnvelope(
    String eventId,
    String eventType,
    int schemaVersion,
    String producer,
    String causationId,
    String correlationId,
    Instant occurredAt,
    Object payload
) {
    public EventEnvelope {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (producer == null || producer.isBlank()) {
            throw new IllegalArgumentException("producer is required");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
    }
}
