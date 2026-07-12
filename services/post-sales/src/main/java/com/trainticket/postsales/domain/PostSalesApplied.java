package com.trainticket.postsales.domain;

public record PostSalesApplied(
    String caseId,
    String journeyOrderId,
    PostSalesCaseType caseType,
    String resultSummary,
    EventMetadata metadata
) implements PostSalesEvent { }
