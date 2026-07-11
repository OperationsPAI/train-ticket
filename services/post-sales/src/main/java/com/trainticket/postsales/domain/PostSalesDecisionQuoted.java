package com.trainticket.postsales.domain;

public record PostSalesDecisionQuoted(
    String caseId,
    DecisionKind decisionKind,
    boolean eligible,
    String ruleSnapshotRef,
    RefundAssessment refundAssessment,
    ChangeAssessment changeAssessment,
    EventMetadata metadata
) implements PostSalesEvent { }
