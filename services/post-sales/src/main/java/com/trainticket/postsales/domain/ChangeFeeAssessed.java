package com.trainticket.postsales.domain;

public record ChangeFeeAssessed(
    String caseId,
    ChangeAssessment assessment,
    EventMetadata metadata
) implements PostSalesEvent { }
