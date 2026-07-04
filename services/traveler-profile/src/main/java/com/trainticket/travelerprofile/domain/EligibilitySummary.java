package com.trainticket.travelerprofile.domain;

import java.time.Instant;
import java.util.Objects;

public final class EligibilitySummary {
    private final String eligibilityId;
    private final String eligibilityType;
    private final String eligibilitySource;
    private final String evidenceHash;
    private final Instant validFrom;
    private final Instant validUntil;
    private boolean revoked;
    private String revocationReason;

    public EligibilitySummary(
        String eligibilityId,
        String eligibilityType,
        String eligibilitySource,
        String evidenceHash,
        Instant validFrom,
        Instant validUntil
    ) {
        this.eligibilityId = requireText(eligibilityId, "eligibilityId");
        this.eligibilityType = requireText(eligibilityType, "eligibilityType");
        this.eligibilitySource = requireText(eligibilitySource, "eligibilitySource");
        this.evidenceHash = requireText(evidenceHash, "evidenceHash");
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom is required");
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil is required");
        this.revoked = false;
        if (!validUntil.isAfter(validFrom)) {
            throw new DomainRuleViolation("eligibility validUntil must be after validFrom");
        }
    }

    public String eligibilityId() { return eligibilityId; }
    public String eligibilityType() { return eligibilityType; }
    public String eligibilitySource() { return eligibilitySource; }
    public String evidenceHash() { return evidenceHash; }
    public Instant validFrom() { return validFrom; }
    public Instant validUntil() { return validUntil; }
    public boolean revoked() { return revoked; }
    public String revocationReason() { return revocationReason; }

    public void revoke(String reason) {
        if (revoked) {
            return;
        }
        this.revoked = true;
        this.revocationReason = requireText(reason, "reason");
    }

    public boolean isEffectiveAt(Instant moment) {
        if (revoked) {
            return false;
        }
        return !moment.isBefore(validFrom) && !moment.isAfter(validUntil);
    }

    public void requireEffectiveForNewQuote(Instant moment) {
        if (revoked) {
            throw new DomainRuleViolation("eligibility " + eligibilityId + " has been revoked: " + revocationReason);
        }
        if (moment.isBefore(validFrom)) {
            throw new DomainRuleViolation("eligibility " + eligibilityId + " is not yet valid until " + validFrom);
        }
        if (moment.isAfter(validUntil)) {
            throw new DomainRuleViolation("eligibility " + eligibilityId + " expired at " + validUntil);
        }
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
