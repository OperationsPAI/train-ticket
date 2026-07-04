package com.trainticket.journeyorder.domain;

import java.util.Objects;

public record TravelerRef(
    String travelerId,
    String travelerType,
    String maskedDocumentRef,
    EligibilityRef eligibilityRef
) {
    public TravelerRef {
        requireText(travelerId, "travelerId");
        requireText(travelerType, "travelerType");
    }

    public TravelerRef(String travelerId, String travelerType) {
        this(travelerId, travelerType, null, null);
    }

    public TravelerRef(String travelerId, String travelerType, String maskedDocumentRef) {
        this(travelerId, travelerType, maskedDocumentRef, null);
    }

    // For backward compatibility
    public String maskedDocumentRef() {
        return maskedDocumentRef;
    }

    public String qualificationVersion() {
        return eligibilityRef != null ? eligibilityRef.eligibilityId() : null;
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
    }
}
