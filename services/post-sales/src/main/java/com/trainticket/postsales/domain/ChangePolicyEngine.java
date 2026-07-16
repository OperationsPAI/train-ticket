package com.trainticket.postsales.domain;

import java.time.Instant;
import java.util.Objects;

public final class ChangePolicyEngine {
    private static final Money DEFAULT_CHANGE_FEE = Money.of("15.00", "CNY");

    public ChangeAssessment evaluateChange(
        Money originalFare,
        Money newFare,
        Instant requestTime,
        Instant departureTime,
        int changeCount
    ) {
        requirePolicyInputs(originalFare, requestTime, departureTime, changeCount);
        Objects.requireNonNull(newFare, "newFare is required").compareTo(originalFare);

        Money changeFee = defaultChangeFee(originalFare);
        Money fareIncrease = newFare.compareTo(originalFare) > 0 ? RefundWaterfall.subtract(newFare, originalFare) : Money.zero(originalFare.currency());
        Money fareDecrease = originalFare.compareTo(newFare) > 0 ? RefundWaterfall.subtract(originalFare, newFare) : Money.zero(originalFare.currency());
        Money grossPayable = changeFee.add(fareIncrease);
        Money netPayable = grossPayable.compareTo(fareDecrease) > 0 ? RefundWaterfall.subtract(grossPayable, fareDecrease) : Money.zero(originalFare.currency());
        Money netRefundable = fareDecrease.compareTo(grossPayable) > 0 ? RefundWaterfall.subtract(fareDecrease, grossPayable) : Money.zero(originalFare.currency());
        Money fareDifference = fareIncrease.isZero() ? fareDecrease : fareIncrease;
        return new ChangeAssessment(changeFee, fareDifference, netPayable, netRefundable, "flat default change fee applied; fare difference netted");
    }

    public ChangeAssessment evaluateQuotedChange(
        Money originalFare,
        Money quotedAmountDue,
        Money quotedRefundable,
        Instant requestTime,
        Instant departureTime,
        int changeCount
    ) {
        requirePolicyInputs(originalFare, requestTime, departureTime, changeCount);
        Objects.requireNonNull(quotedAmountDue, "quotedAmountDue is required").compareTo(originalFare);
        Objects.requireNonNull(quotedRefundable, "quotedRefundable is required").compareTo(originalFare);
        if (!quotedAmountDue.isZero() && !quotedRefundable.isZero()) {
            throw new DomainRuleViolation("a change quote cannot be both payable and refundable");
        }

        Money changeFee = defaultChangeFee(originalFare);
        Money fareDifference = quotedFareDifference(changeFee, quotedAmountDue, quotedRefundable);
        return new ChangeAssessment(changeFee, fareDifference, quotedAmountDue, quotedRefundable, "fare-pricing change quote applied with flat default change fee");
    }

    private static void requirePolicyInputs(Money originalFare, Instant requestTime, Instant departureTime, int changeCount) {
        Objects.requireNonNull(originalFare, "originalFare is required");
        Objects.requireNonNull(requestTime, "requestTime is required");
        Objects.requireNonNull(departureTime, "departureTime is required");
        if (changeCount < 0) {
            throw new DomainRuleViolation("changeCount must not be negative");
        }
    }

    private static Money defaultChangeFee(Money fare) {
        return DEFAULT_CHANGE_FEE.currency().equals(fare.currency()) ? DEFAULT_CHANGE_FEE : Money.zero(fare.currency());
    }

    private static Money quotedFareDifference(Money changeFee, Money quotedAmountDue, Money quotedRefundable) {
        if (!quotedRefundable.isZero()) {
            return changeFee.add(quotedRefundable);
        }
        if (quotedAmountDue.compareTo(changeFee) > 0) {
            return RefundWaterfall.subtract(quotedAmountDue, changeFee);
        }
        if (changeFee.compareTo(quotedAmountDue) > 0) {
            return RefundWaterfall.subtract(changeFee, quotedAmountDue);
        }
        return Money.zero(changeFee.currency());
    }
}
