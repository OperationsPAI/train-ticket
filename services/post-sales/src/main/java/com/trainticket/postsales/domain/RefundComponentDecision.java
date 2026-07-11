package com.trainticket.postsales.domain;

import java.util.Objects;

public record RefundComponentDecision(
    String componentType,
    Money originalAmount,
    Money refundableAmount,
    Money retainedAmount,
    boolean refundable,
    String retainReason
) {
    public RefundComponentDecision {
        componentType = PostSalesScope.requireText(componentType, "componentType");
        Objects.requireNonNull(originalAmount, "originalAmount is required");
        Objects.requireNonNull(refundableAmount, "refundableAmount is required");
        Objects.requireNonNull(retainedAmount, "retainedAmount is required");
        originalAmount.compareTo(refundableAmount);
        originalAmount.compareTo(retainedAmount);
        retainReason = retainReason == null ? "" : retainReason;
    }
}
