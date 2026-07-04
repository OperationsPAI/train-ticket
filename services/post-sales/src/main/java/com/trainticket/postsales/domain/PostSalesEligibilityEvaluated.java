package com.trainticket.postsales.domain;

public record PostSalesEligibilityEvaluated(
    String caseId,
    boolean eligible,
    String reasonCode,
    String ruleSnapshotRef,
    String ruleVersion,
    EventMetadata metadata
) implements PostSalesEvent { }
