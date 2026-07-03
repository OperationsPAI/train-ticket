package com.trainticket.postsales.domain;

public record PostSalesApproved(
    String caseId,
    DecisionKind decisionKind,
    String approvalRef,
    EventMetadata metadata
) implements PostSalesEvent { }
