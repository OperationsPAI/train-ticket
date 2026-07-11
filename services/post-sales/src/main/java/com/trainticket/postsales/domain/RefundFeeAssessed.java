package com.trainticket.postsales.domain;

public record RefundFeeAssessed(
    String caseId,
    RefundAssessment assessment,
    EventMetadata metadata
) implements PostSalesEvent { }
