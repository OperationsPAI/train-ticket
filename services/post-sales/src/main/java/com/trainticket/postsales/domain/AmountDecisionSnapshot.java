package com.trainticket.postsales.domain;

import java.util.Objects;

public record AmountDecisionSnapshot(
    Money feeAmount,
    Money refundAmount,
    Money extraChargeAmount,
    String explanation
) {
    public AmountDecisionSnapshot {
        Objects.requireNonNull(feeAmount, "feeAmount is required");
        Objects.requireNonNull(refundAmount, "refundAmount is required");
        Objects.requireNonNull(extraChargeAmount, "extraChargeAmount is required");
        explanation = PostSalesScope.requireText(explanation, "explanation");
        feeAmount.compareTo(refundAmount);
        feeAmount.compareTo(extraChargeAmount);
        if (!refundAmount.isZero() && !extraChargeAmount.isZero()) {
            throw new DomainRuleViolation("a single post-sales amount decision cannot request both refund and extra charge");
        }
    }

    public static AmountDecisionSnapshot refund(Money feeAmount, Money refundAmount, String explanation) {
        return new AmountDecisionSnapshot(feeAmount, refundAmount, Money.zero(refundAmount.currency()), explanation);
    }

    public static AmountDecisionSnapshot extraCharge(Money feeAmount, Money extraChargeAmount, String explanation) {
        return new AmountDecisionSnapshot(feeAmount, Money.zero(extraChargeAmount.currency()), extraChargeAmount, explanation);
    }
}
