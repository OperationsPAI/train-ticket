package com.trainticket.postsales.domain;

public record PostSalesStepSucceeded(
    String caseId,
    PostSalesStepType stepType,
    String externalRef,
    EventMetadata metadata
) implements PostSalesEvent { }
