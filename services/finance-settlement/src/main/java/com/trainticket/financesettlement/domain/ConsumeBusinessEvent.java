package com.trainticket.financesettlement.domain;

import java.time.Instant;
import java.util.Objects;

public record ConsumeBusinessEvent(String eventId, String source, String eventType, Instant consumedAt) {
    public ConsumeBusinessEvent {
        requireText(eventId, "eventId");
        requireText(source, "source");
        requireText(eventType, "eventType");
        Objects.requireNonNull(consumedAt, "consumedAt is required");
    }
    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank())
            throw new DomainRuleViolation(name + " must not be blank");
    }
}
