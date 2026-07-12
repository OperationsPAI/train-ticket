package com.trainticket.postsales.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record RefundAssessment(
    Money refundableAmount,
    Money penaltyAmount,
    BigDecimal penaltyPct,
    String tierApplied,
    String explanation,
    RefundClassification classification,
    List<RefundComponentDecision> componentDecisions
) {
    public RefundAssessment {
        Objects.requireNonNull(refundableAmount, "refundableAmount is required");
        Objects.requireNonNull(penaltyAmount, "penaltyAmount is required");
        penaltyPct = Objects.requireNonNull(penaltyPct, "penaltyPct is required").stripTrailingZeros();
        tierApplied = PostSalesScope.requireText(tierApplied, "tierApplied");
        explanation = PostSalesScope.requireText(explanation, "explanation");
        classification = Objects.requireNonNull(classification, "classification is required");
        componentDecisions = List.copyOf(Objects.requireNonNull(componentDecisions, "componentDecisions are required"));
        refundableAmount.compareTo(penaltyAmount);
    }
}
