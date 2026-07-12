package com.trainticket.postsales.domain;

import java.util.Objects;

public record ChangeAssessment(
    Money changeFee,
    Money fareDifference,
    Money netPayable,
    Money netRefundable,
    String explanation
) {
    public ChangeAssessment {
        Objects.requireNonNull(changeFee, "changeFee is required");
        Objects.requireNonNull(fareDifference, "fareDifference is required");
        Objects.requireNonNull(netPayable, "netPayable is required");
        Objects.requireNonNull(netRefundable, "netRefundable is required");
        explanation = PostSalesScope.requireText(explanation, "explanation");
        changeFee.compareTo(fareDifference);
        changeFee.compareTo(netPayable);
        changeFee.compareTo(netRefundable);
        if (!netPayable.isZero() && !netRefundable.isZero()) {
            throw new DomainRuleViolation("a change assessment cannot be both payable and refundable");
        }
    }
}
