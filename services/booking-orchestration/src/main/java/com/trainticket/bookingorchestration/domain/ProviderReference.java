package com.trainticket.bookingorchestration.domain;

/** Normalized provider booking handle. Raw supplier status codes and payloads remain in Provider Integration. */
public record ProviderReference(String providerId, String reservationId, String displayReference) {
    public ProviderReference {
        providerId = requireText(providerId, "providerId");
        reservationId = requireText(reservationId, "reservationId");
        displayReference = requireText(displayReference, "displayReference");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
