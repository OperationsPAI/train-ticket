package com.trainticket.postsales.domain;

public record PostSalesRejected(
    String caseId,
    String reasonCode,
    EventMetadata metadata
) implements PostSalesEvent { }
