package com.trainticket.platformkit.messaging;

import java.time.Instant;
import java.util.Map;

public final class EnvelopeFactory {
    private EnvelopeFactory() {
    }

    public static EventEnvelope create(String eventType, Instant occurredAt, String sourceCommandId, String correlationId, String producer) {
        return create(eventType, occurredAt, sourceCommandId, correlationId, producer, Map.of());
    }

    public static EventEnvelope create(String eventType, Instant occurredAt, String sourceCommandId, String correlationId, String producer, Object payload) {
        return new EventEnvelope(
            PrefixedIds.newEventId(),
            eventType,
            occurredAt,
            canonicalCorrelationId(correlationId),
            canonicalCausationId(sourceCommandId, correlationId),
            producer,
            1,
            payload == null ? Map.of() : payload
        );
    }

    private static String canonicalCorrelationId(String correlationId) {
        if (PrefixedIds.isCorrelationId(correlationId)) {
            return correlationId;
        }
        return PrefixedIds.newCorrelationId();
    }

    private static String canonicalCausationId(String sourceCommandId, String correlationId) {
        if (PrefixedIds.isCausationId(sourceCommandId)) {
            return sourceCommandId;
        }
        if (sourceCommandId != null && !sourceCommandId.isBlank()) {
            String normalized = sourceCommandId.startsWith("evt-") ? sourceCommandId.substring(4) : sourceCommandId;
            normalized = normalized.startsWith("cmd-") ? normalized.substring(4) : normalized;
            if (com.trainticket.platformkit.idempotency.UuidV7.isValid(normalized)) {
                return "cmd-" + normalized;
            }
        }
        if (PrefixedIds.isCausationId(correlationId)) {
            return correlationId;
        }
        return PrefixedIds.newCommandId();
    }
}
