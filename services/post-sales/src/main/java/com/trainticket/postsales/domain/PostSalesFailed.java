package com.trainticket.postsales.domain;

public record PostSalesFailed(
    String caseId,
    String journeyOrderId,
    String reason,
    String failedStepRef,
    EventMetadata metadata
) implements PostSalesEvent { }
