package com.trainticket.postsales.domain;

public record PostSalesRequested(
    String caseId,
    String journeyOrderId,
    PostSalesCaseType caseType,
    PostSalesScope scope,
    String reasonCode,
    EventMetadata metadata
) implements PostSalesEvent { }
