package com.trainticket.payment.application;

import java.time.Instant;
import java.util.Objects;

public record ReservationPaymentRequest(
    String eventId,
    String segmentBookingId,
    String journeyOrderId,
    String segmentRef,
    String travelerRef,
    String idempotencyKey,
    String correlationId,
    Instant requestedAt
) {
    public ReservationPaymentRequest {
        eventId = requireText(eventId, "eventId");
        segmentBookingId = requireText(segmentBookingId, "segmentBookingId");
        journeyOrderId = requireText(journeyOrderId, "journeyOrderId");
        segmentRef = requireText(segmentRef, "segmentRef");
        travelerRef = requireText(travelerRef, "travelerRef");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        correlationId = requireText(correlationId, "correlationId");
        Objects.requireNonNull(requestedAt, "requestedAt is required");
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
