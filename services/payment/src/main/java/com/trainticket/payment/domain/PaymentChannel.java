package com.trainticket.payment.domain;

import java.util.Objects;

public record PaymentChannel(
    String channelId,
    String channelType,
    long maxAmountMinor,
    int timeoutSeconds,
    boolean enabled,
    int weight
) {
    public PaymentChannel {
        requireText(channelId, "channelId");
        requireText(channelType, "channelType");
        if (maxAmountMinor <= 0) {
            throw new DomainRuleViolation("channel max amount must be positive");
        }
        if (timeoutSeconds <= 0) {
            throw new DomainRuleViolation("channel timeout must be positive");
        }
        if (weight < 0) {
            throw new DomainRuleViolation("channel weight must not be negative");
        }
    }

    public boolean canCarry(Money amount) {
        Objects.requireNonNull(amount, "amount is required");
        return enabled && amount.toMinorUnits() <= maxAmountMinor;
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
