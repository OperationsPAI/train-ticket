package com.trainticket.adminaudit.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import java.util.Map;

final class InboundEventPayload {
    private final Map<String, Object> payload;

    private InboundEventPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    @SuppressWarnings("unchecked")
    static InboundEventPayload from(EventEnvelope envelope) {
        if (envelope.payload() instanceof Map<?, ?> map) {
            return new InboundEventPayload((Map<String, Object>) map);
        }
        throw new ValidationException("payload must be an object");
    }

    String requiredText(String field) {
        Object value = payload.get(field);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new ValidationException(field + " is required");
    }

    boolean requiredBoolean(String field) {
        Object value = payload.get(field);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new ValidationException(field + " is required");
    }

    String optionalText(String field) {
        Object value = payload.get(field);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }
}
