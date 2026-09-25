package com.trainticket.walletpromotion.domain;

import java.time.Instant;
import java.util.List;

public record BenefitRedemption(
    String redemptionId,
    String benefitId,
    String accountId,
    Money amount,
    String redemptionRef,
    String reservationRef,
    BenefitUseReference useReference,
    BusinessReason businessReason,
    Instant redeemedAt,
    long reversedMinorUnits
) {
    public BenefitRedemption {
        amount.requirePositive("amount");
        if (redemptionRef == null || redemptionRef.isBlank()) throw new DomainException("redemptionRef is required");
        if (benefitId == null || benefitId.isBlank()) throw new DomainException("benefitId is required");
        if (accountId == null || accountId.isBlank()) throw new DomainException("accountId is required");
        if (businessReason == null) throw new DomainException("businessReason is required");
        if (redeemedAt == null) throw new DomainException("redeemedAt is required");
        if (reversedMinorUnits < 0 || reversedMinorUnits > amount.minorUnits()) {
            throw new DomainException("reversed amount is outside redemption amount");
        }
        if (useReference == null) {
            String historicalOrder = "ORDER".equals(businessReason.referenceType())
                ? businessReason.referenceId()
                : redemptionRef.startsWith("ord-") ? redemptionRef : null;
            if (historicalOrder != null) useReference = new BenefitUseReference(historicalOrder, null, List.of());
        }
        if (useReference == null) throw new DomainException("useReference is required");
    }
    public long unreversedMinorUnits() { return amount.minorUnits() - reversedMinorUnits; }

    public boolean sameRequest(Money requestedAmount, String requestedRedemptionRef, String requestedReservationRef,
                               BenefitUseReference requestedUseReference, BusinessReason requestedReason) {
        boolean reservationMatches = java.util.Objects.equals(reservationRef, requestedReservationRef)
            || (reservationRef == null && redemptionRef.equals(requestedReservationRef));
        return amount.equals(requestedAmount)
            && redemptionRef.equals(requestedRedemptionRef)
            && reservationMatches
            && java.util.Objects.equals(useReference, requestedUseReference)
            && businessReason.equals(requestedReason);
    }
}
