package com.trainticket.postsales.domain;

import java.time.Instant;
import java.util.Objects;

public record PostSalesDecision(
    String caseId,
    int version,
    DecisionKind kind,
    boolean eligible,
    String reasonCode,
    RuleEvaluationSnapshot ruleSnapshot,
    AmountDecisionSnapshot amountSnapshot,
    ChangeFlowSnapshot changeFlowSnapshot,
    Instant quotedAt,
    Instant expiresAt
) {
    public PostSalesDecision {
        caseId = PostSalesScope.requireText(caseId, "caseId");
        if (version < 1) {
            throw new DomainRuleViolation("decision version must be positive");
        }
        kind = Objects.requireNonNull(kind, "kind is required");
        reasonCode = PostSalesScope.requireText(reasonCode, "reasonCode");
        Objects.requireNonNull(ruleSnapshot, "ruleSnapshot is required");
        Objects.requireNonNull(amountSnapshot, "amountSnapshot is required");
        Objects.requireNonNull(quotedAt, "quotedAt is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        if (!expiresAt.isAfter(quotedAt)) {
            throw new DomainRuleViolation("decision quote must expire after quotedAt");
        }
        if (kind == DecisionKind.CHANGE && changeFlowSnapshot == null) {
            throw new DomainRuleViolation("change decisions must include a conservative change flow snapshot");
        }
        if (kind != DecisionKind.CHANGE && changeFlowSnapshot != null) {
            throw new DomainRuleViolation("only change decisions may include a change flow snapshot");
        }
    }

    public static PostSalesDecision refund(String caseId, boolean eligible, String reasonCode, RuleEvaluationSnapshot ruleSnapshot, AmountDecisionSnapshot amountSnapshot, Instant quotedAt, Instant expiresAt) {
        return new PostSalesDecision(caseId, 1, DecisionKind.REFUND, eligible, reasonCode, ruleSnapshot, amountSnapshot, null, quotedAt, expiresAt);
    }

    public static PostSalesDecision change(String caseId, boolean eligible, String reasonCode, RuleEvaluationSnapshot ruleSnapshot, AmountDecisionSnapshot amountSnapshot, ChangeFlowSnapshot changeFlowSnapshot, Instant quotedAt, Instant expiresAt) {
        return new PostSalesDecision(caseId, 1, DecisionKind.CHANGE, eligible, reasonCode, ruleSnapshot, amountSnapshot, changeFlowSnapshot, quotedAt, expiresAt);
    }
}
