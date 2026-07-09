package com.trainticket.walletpromotion.domain;

import java.time.Instant;

public record BenefitRedemption(String redemptionId, String benefitId, String accountId, Money amount, String redemptionRef, BusinessReason businessReason, Instant redeemedAt, long reversedMinorUnits) {
    public BenefitRedemption {
        amount.requirePositive("amount");
        if (redemptionRef == null || redemptionRef.isBlank()) throw new DomainException("redemptionRef is required");
    }
    public long unreversedMinorUnits() { return amount.minorUnits() - reversedMinorUnits; }
}
