package com.trainticket.postsales.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record RuleEvaluationSnapshot(
    String farePricingEvaluationRef,
    String originalOfferRuleSnapshotRef,
    String fareRuleVersion,
    Instant evaluatedAt,
    Map<String, String> inputSnapshotRefs
) {
    public RuleEvaluationSnapshot {
        farePricingEvaluationRef = PostSalesScope.requireText(farePricingEvaluationRef, "farePricingEvaluationRef");
        originalOfferRuleSnapshotRef = PostSalesScope.requireText(originalOfferRuleSnapshotRef, "originalOfferRuleSnapshotRef");
        fareRuleVersion = PostSalesScope.requireText(fareRuleVersion, "fareRuleVersion");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt is required");
        inputSnapshotRefs = Map.copyOf(Objects.requireNonNull(inputSnapshotRefs, "inputSnapshotRefs are required"));
        if (inputSnapshotRefs.isEmpty()) {
            throw new DomainRuleViolation("rule evaluation snapshot must freeze the external input snapshot references");
        }
    }
}
