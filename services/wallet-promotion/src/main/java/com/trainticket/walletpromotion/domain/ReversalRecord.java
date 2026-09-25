package com.trainticket.walletpromotion.domain;

import java.time.Instant;

public record ReversalRecord(String reversalId, String redemptionId, String reversalRef, Money amount, BusinessReason businessReason, Instant reversedAt) {
    public ReversalRecord {
        if (reversalId == null || reversalId.isBlank()) throw new DomainException("reversalId is required");
        if (redemptionId == null || redemptionId.isBlank()) throw new DomainException("redemptionId is required");
        if (reversalRef == null || reversalRef.isBlank()) reversalRef = businessReason == null ? null : businessReason.key();
        if (reversalRef == null || reversalRef.isBlank()) throw new DomainException("reversalRef is required");
        amount.requirePositive("amount");
        if (businessReason == null) throw new DomainException("businessReason is required");
        if (reversedAt == null) throw new DomainException("reversedAt is required");
    }

    public boolean sameRequest(Money requestedAmount, BusinessReason requestedReason) {
        return amount.equals(requestedAmount) && businessReason.equals(requestedReason);
    }
}
