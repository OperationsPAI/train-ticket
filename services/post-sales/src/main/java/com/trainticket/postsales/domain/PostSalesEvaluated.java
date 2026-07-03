package com.trainticket.postsales.domain;

public record PostSalesEvaluated(
    String caseId,
    DecisionKind decisionKind,
    boolean eligible,
    String farePricingEvaluationRef,
    EventMetadata metadata
) implements PostSalesEvent { }
