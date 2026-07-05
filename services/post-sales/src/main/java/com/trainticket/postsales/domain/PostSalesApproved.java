package com.trainticket.postsales.domain;

public record PostSalesApproved(
    String caseId,
    String journeyOrderId,
    DecisionKind decisionKind,
    String approvalRef,
    EventMetadata metadata
) implements PostSalesEvent { }
