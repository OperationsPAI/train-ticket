package com.trainticket.journeyorder.domain;

import java.time.Instant;
import java.util.Objects;

public record EligibilityRef(
    String eligibilityId,
    String eligibilityType,
    String eligibilitySource,
    String evidenceHash,
    Instant verifiedAt
) {
    public EligibilityRef {
        requireText(eligibilityId, "eligibilityId");
        requireText(eligibilityType, "eligibilityType");
        requireText(eligibilitySource, "eligibilitySource");
    }

    public EligibilityRef(String eligibilityId, String eligibilityType, String eligibilitySource) {
        this(eligibilityId, eligibilityType, eligibilitySource, null, null);
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
    }
}
