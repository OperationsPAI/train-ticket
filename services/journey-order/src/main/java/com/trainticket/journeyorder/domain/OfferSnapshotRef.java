package com.trainticket.journeyorder.domain;

import java.time.Instant;
import java.util.Objects;

public record OfferSnapshotRef(
    String offerId,
    int offerVersion,
    Instant quotedAt,
    Instant expiresAt,
    String priceSnapshotRef,
    String ruleSnapshotId,
    /**
     * The distance the accepted offer's fare quote was priced from, when its rule
     * set prices by distance. Null when the quote was priced from the flat base
     * fare, and null on an order written before this field existed.
     */
    Double distanceKm
) {
    public OfferSnapshotRef {
        requireText(offerId, "offerId");
        if (offerVersion < 1) {
            throw new DomainRuleViolation("offerVersion must be positive");
        }
        Objects.requireNonNull(quotedAt, "quotedAt is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        requireText(priceSnapshotRef, "priceSnapshotRef");
        requireText(ruleSnapshotId, "ruleSnapshotId");
        if (!expiresAt.isAfter(quotedAt)) {
            throw new DomainRuleViolation("offer expiresAt must be after quotedAt");
        }
    }

    public void requireValidAt(Instant now) {
        Objects.requireNonNull(now, "now is required");
        if (!now.isBefore(expiresAt)) {
            throw new DomainRuleViolation("cannot create JourneyOrder from expired offer " + offerId);
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
    }
}
