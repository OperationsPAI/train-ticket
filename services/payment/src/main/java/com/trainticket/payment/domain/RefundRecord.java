package com.trainticket.payment.domain;

import java.time.Instant;
import java.util.Objects;

public record RefundRecord(
    String refundId,
    Money amount,
    ChannelRef channelRef,
    RefundStatus status,
    Instant createdAt
) {
    public RefundRecord {
        requireText(refundId, "refundId");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (amount.isZero()) {
            throw new DomainRuleViolation("refund record amount must be positive");
        }
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
