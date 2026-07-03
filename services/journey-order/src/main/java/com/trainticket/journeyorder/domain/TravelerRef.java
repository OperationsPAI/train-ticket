package com.trainticket.journeyorder.domain;

import java.util.Objects;

public record TravelerRef(
    String travelerId,
    String maskedDocumentRef,
    String travelerType,
    String qualificationVersion
) {
    public TravelerRef {
        requireText(travelerId, "travelerId");
        requireText(maskedDocumentRef, "maskedDocumentRef");
        requireText(travelerType, "travelerType");
        requireText(qualificationVersion, "qualificationVersion");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
    }
}
