package com.trainticket.postsales.domain;

public record PostSalesDecisionQuoted(
    String caseId,
    DecisionKind decisionKind,
    boolean eligible,
    String ruleSnapshotRef,
    EventMetadata metadata
) implements PostSalesEvent { }
