package com.trainticket.walletpromotion.domain;

import java.time.Instant;
import java.util.Objects;

public record ReservationRelease(
    String releaseId,
    String benefitId,
    String reservationRef,
    String releaseRef,
    Money amount,
    BusinessReason businessReason,
    Instant releasedAt
) {
    public ReservationRelease {
        requireText(releaseId, "releaseId");
        requireText(benefitId, "benefitId");
        requireText(reservationRef, "reservationRef");
        if (releaseRef == null || releaseRef.isBlank()) releaseRef = businessReason == null ? null : businessReason.key();
        requireText(releaseRef, "releaseRef");
        amount.requirePositive("amount");
        Objects.requireNonNull(businessReason, "businessReason is required");
        Objects.requireNonNull(releasedAt, "releasedAt is required");
    }

    public boolean sameRequest(String requestedReservationRef, Money requestedAmount, BusinessReason requestedReason) {
        return reservationRef.equals(requestedReservationRef)
            && amount.equals(requestedAmount)
            && businessReason.equals(requestedReason);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new DomainException(name + " is required");
    }
}
