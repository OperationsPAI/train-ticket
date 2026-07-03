package com.trainticket.postsales.domain;

public record PostSalesManualReviewRequired(
    String caseId,
    String reason,
    EventMetadata metadata
) implements PostSalesEvent { }
