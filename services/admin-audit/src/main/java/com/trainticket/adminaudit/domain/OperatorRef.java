package com.trainticket.adminaudit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Operator identity value object.
 */
public record OperatorRef(String operatorId, String displayName) {
    public OperatorRef {
        operatorId = requireText(operatorId, "operatorId");
        displayName = requireText(displayName, "displayName");
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
