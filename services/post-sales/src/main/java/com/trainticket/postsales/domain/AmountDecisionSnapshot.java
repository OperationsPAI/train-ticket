package com.trainticket.postsales.domain;

import java.util.List;
import java.util.Objects;

public record AmountDecisionSnapshot(
    Money feeAmount,
    Money refundAmount,
    Money extraChargeAmount,
    String explanation,
    List<RefundComponentDecision> componentDecisions
) {
    public AmountDecisionSnapshot {
        Objects.requireNonNull(feeAmount, "feeAmount is required");
        Objects.requireNonNull(refundAmount, "refundAmount is required");
        Objects.requireNonNull(extraChargeAmount, "extraChargeAmount is required");
        explanation = PostSalesScope.requireText(explanation, "explanation");
        componentDecisions = componentDecisions == null ? List.of() : List.copyOf(componentDecisions);
        feeAmount.compareTo(refundAmount);
        feeAmount.compareTo(extraChargeAmount);
        if (!refundAmount.isZero() && !extraChargeAmount.isZero()) {
            throw new DomainRuleViolation("a single post-sales amount decision cannot request both refund and extra charge");
        }
    }

    public AmountDecisionSnapshot(Money feeAmount, Money refundAmount, Money extraChargeAmount, String explanation) {
        this(feeAmount, refundAmount, extraChargeAmount, explanation, List.of());
    }

    public static AmountDecisionSnapshot refund(Money feeAmount, Money refundAmount, String explanation) {
        return refund(feeAmount, refundAmount, explanation, List.of());
    }

    public static AmountDecisionSnapshot refund(Money feeAmount, Money refundAmount, String explanation, List<RefundComponentDecision> componentDecisions) {
        return new AmountDecisionSnapshot(feeAmount, refundAmount, Money.zero(refundAmount.currency()), explanation, componentDecisions);
    }

    public static AmountDecisionSnapshot extraCharge(Money feeAmount, Money extraChargeAmount, String explanation) {
        return new AmountDecisionSnapshot(feeAmount, Money.zero(extraChargeAmount.currency()), extraChargeAmount, explanation, List.of());
    }
}
