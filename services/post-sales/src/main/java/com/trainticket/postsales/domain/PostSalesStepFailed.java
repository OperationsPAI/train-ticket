package com.trainticket.postsales.domain;

public record PostSalesStepFailed(
    String caseId,
    PostSalesStepType stepType,
    String reason,
    boolean manualRequired,
    EventMetadata metadata
) implements PostSalesEvent { }
