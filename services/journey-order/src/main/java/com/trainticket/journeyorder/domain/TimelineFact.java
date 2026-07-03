package com.trainticket.journeyorder.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record TimelineFact(
    String factId,
    String factType,
    Instant occurredAt,
    String actor,
    String reason,
    Map<String, String> attributes
) {
    public TimelineFact {
        factId = requireText(factId, "factId");
        factType = requireText(factType, "factType");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        actor = requireText(actor, "actor");
        reason = requireText(reason, "reason");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes are required"));
    }

    public static TimelineFact of(String factType, Instant occurredAt, String actor, String reason, Map<String, String> attributes) {
        return new TimelineFact(UUID.randomUUID().toString(), factType, occurredAt, actor, reason, attributes);
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
