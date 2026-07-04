package com.trainticket.postsales.domain;

public record PostSalesCaseOpened(
    String caseId,
    String journeyOrderId,
    PostSalesCaseType caseType,
    PostSalesScope scope,
    String reasonCode,
    String actorRef,
    EventMetadata metadata
) implements PostSalesEvent { }
