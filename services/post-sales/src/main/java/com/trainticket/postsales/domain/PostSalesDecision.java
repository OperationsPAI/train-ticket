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
    RefundAssessment refundAssessment,
    ChangeAssessment changeAssessment,
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
        if (kind == DecisionKind.REFUND && refundAssessment == null) {
            throw new DomainRuleViolation("refund decisions must include a refund assessment");
        }
        if (kind != DecisionKind.REFUND && refundAssessment != null) {
            throw new DomainRuleViolation("only refund decisions may include a refund assessment");
        }
        if (kind == DecisionKind.CHANGE && changeAssessment == null) {
            throw new DomainRuleViolation("change decisions must include a change assessment");
        }
        if (kind != DecisionKind.CHANGE && changeAssessment != null) {
            throw new DomainRuleViolation("only change decisions may include a change assessment");
        }
        if (kind == DecisionKind.CHANGE && changeFlowSnapshot == null) {
            throw new DomainRuleViolation("change decisions must include a conservative change flow snapshot");
        }
        if (kind != DecisionKind.CHANGE && changeFlowSnapshot != null) {
            throw new DomainRuleViolation("only change decisions may include a change flow snapshot");
        }
    }

    public static PostSalesDecision refund(String caseId, boolean eligible, String reasonCode, RuleEvaluationSnapshot ruleSnapshot, AmountDecisionSnapshot amountSnapshot, Instant quotedAt, Instant expiresAt) {
        RefundAssessment assessment = new RefundAssessment(amountSnapshot.refundAmount(), amountSnapshot.feeAmount(), java.math.BigDecimal.ZERO, "LEGACY", amountSnapshot.explanation(), RefundClassification.VOLUNTARY, amountSnapshot.componentDecisions());
        return refund(caseId, eligible, reasonCode, ruleSnapshot, amountSnapshot, assessment, quotedAt, expiresAt);
    }

    public static PostSalesDecision refund(String caseId, boolean eligible, String reasonCode, RuleEvaluationSnapshot ruleSnapshot, AmountDecisionSnapshot amountSnapshot, RefundAssessment refundAssessment, Instant quotedAt, Instant expiresAt) {
        return new PostSalesDecision(caseId, 1, DecisionKind.REFUND, eligible, reasonCode, ruleSnapshot, amountSnapshot, null, refundAssessment, null, quotedAt, expiresAt);
    }

    public static PostSalesDecision change(String caseId, boolean eligible, String reasonCode, RuleEvaluationSnapshot ruleSnapshot, AmountDecisionSnapshot amountSnapshot, ChangeFlowSnapshot changeFlowSnapshot, Instant quotedAt, Instant expiresAt) {
        ChangeAssessment assessment = new ChangeAssessment(amountSnapshot.feeAmount(), amountSnapshot.extraChargeAmount().isZero() ? amountSnapshot.refundAmount() : amountSnapshot.extraChargeAmount(), amountSnapshot.extraChargeAmount(), amountSnapshot.refundAmount(), amountSnapshot.explanation());
        return change(caseId, eligible, reasonCode, ruleSnapshot, amountSnapshot, changeFlowSnapshot, assessment, quotedAt, expiresAt);
    }

    public static PostSalesDecision change(String caseId, boolean eligible, String reasonCode, RuleEvaluationSnapshot ruleSnapshot, AmountDecisionSnapshot amountSnapshot, ChangeFlowSnapshot changeFlowSnapshot, ChangeAssessment changeAssessment, Instant quotedAt, Instant expiresAt) {
        return new PostSalesDecision(caseId, 1, DecisionKind.CHANGE, eligible, reasonCode, ruleSnapshot, amountSnapshot, changeFlowSnapshot, null, changeAssessment, quotedAt, expiresAt);
    }
}
