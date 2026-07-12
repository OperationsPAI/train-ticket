package com.trainticket.financesettlement.domain;

import java.time.Instant;
import java.util.Objects;

public record OpenReconciliationCase(
    String orderId, String paymentIntentId, String differenceType,
    Money expectedAmount, Money actualAmount, String description, Instant detectedAt
) {
    public OpenReconciliationCase {
        Objects.requireNonNull(expectedAmount, "expectedAmount is required");
        Objects.requireNonNull(actualAmount, "actualAmount is required");
        requireText(differenceType, "differenceType");
        requireText(description, "description");
        Objects.requireNonNull(detectedAt, "detectedAt is required");
    }
    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank())
            throw new DomainRuleViolation(name + " must not be blank");
    }
}
