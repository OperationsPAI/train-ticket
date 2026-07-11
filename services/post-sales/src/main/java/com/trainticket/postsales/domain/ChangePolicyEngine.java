package com.trainticket.postsales.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ChangePolicyEngine {
    private static final BigDecimal TWENTY_PCT = new BigDecimal("0.20");

    public ChangeAssessment evaluateChange(
        Money originalFare,
        Money newFare,
        Instant requestTime,
        Instant departureTime,
        int changeCount
    ) {
        Objects.requireNonNull(originalFare, "originalFare is required");
        Objects.requireNonNull(newFare, "newFare is required").compareTo(originalFare);
        Objects.requireNonNull(requestTime, "requestTime is required");
        Objects.requireNonNull(departureTime, "departureTime is required");
        if (changeCount < 0) {
            throw new DomainRuleViolation("changeCount must not be negative");
        }

        boolean freeFirstChange = changeCount == 0 && Duration.between(requestTime, departureTime).compareTo(Duration.ofHours(48)) > 0;
        Money changeFee = freeFirstChange ? Money.zero(originalFare.currency()) : RefundWaterfall.percent(originalFare, TWENTY_PCT);
        Money fareIncrease = newFare.compareTo(originalFare) > 0 ? RefundWaterfall.subtract(newFare, originalFare) : Money.zero(originalFare.currency());
        Money fareDecrease = originalFare.compareTo(newFare) > 0 ? RefundWaterfall.subtract(originalFare, newFare) : Money.zero(originalFare.currency());
        Money grossPayable = changeFee.add(fareIncrease);
        Money netPayable = grossPayable.compareTo(fareDecrease) > 0 ? RefundWaterfall.subtract(grossPayable, fareDecrease) : Money.zero(originalFare.currency());
        Money netRefundable = fareDecrease.compareTo(grossPayable) > 0 ? RefundWaterfall.subtract(fareDecrease, grossPayable) : Money.zero(originalFare.currency());
        Money fareDifference = fareIncrease.isZero() ? fareDecrease : fareIncrease;
        String explanation = freeFirstChange
            ? "first change more than 48 hours before departure has no change fee; fare difference netted"
            : "20% change fee applied; fare difference netted";
        return new ChangeAssessment(changeFee, fareDifference, netPayable, netRefundable, explanation);
    }
}
