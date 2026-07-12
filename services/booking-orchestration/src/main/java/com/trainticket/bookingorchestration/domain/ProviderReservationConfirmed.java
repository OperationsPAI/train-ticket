package com.trainticket.bookingorchestration.domain;

import java.util.Map;

/**
 * Normalized Provider Integration fact accepted by Booking Orchestration.
 * Raw supplier codes, signatures and payloads are intentionally not representable here.
 */
public record ProviderReservationConfirmed(String segmentBookingId, ProviderReference providerReference,
                                           String normalizedEvidence, Map<String, String> normalizedAttributes) {
    public ProviderReservationConfirmed {
        segmentBookingId = requireText(segmentBookingId, "segmentBookingId");
        if (providerReference == null) {
            throw new IllegalArgumentException("providerReference is required");
        }
        normalizedEvidence = requireText(normalizedEvidence, "normalizedEvidence");
        normalizedAttributes = Map.copyOf(normalizedAttributes == null ? Map.of() : normalizedAttributes);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
