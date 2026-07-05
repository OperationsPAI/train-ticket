package com.trainticket.postsales.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.postsales.domain.AmountDecisionSnapshot;
import com.trainticket.postsales.domain.DecisionKind;
import com.trainticket.postsales.domain.EventMetadata;
import com.trainticket.postsales.domain.Money;
import com.trainticket.postsales.domain.PostSalesApplied;
import com.trainticket.postsales.domain.PostSalesApproved;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseOpened;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesDecision;
import com.trainticket.postsales.domain.PostSalesEvaluated;
import com.trainticket.postsales.domain.PostSalesEvent;
import com.trainticket.postsales.domain.PostSalesRejected;
import com.trainticket.postsales.domain.PostSalesRequested;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PostSalesMapper {
    public static final String PRODUCER = "post-sales";

    private PostSalesMapper() {
    }

    public static EventEnvelope toEnvelope(PostSalesEvent event) {
        EventMetadata metadata = event.metadata();
        return new EventEnvelope(
            PrefixedIds.isEventId(prefixed(metadata.eventId(), "evt-")) ? prefixed(metadata.eventId(), "evt-") : PrefixedIds.newEventId(),
            event.getClass().getSimpleName(),
            metadata.occurredAt(),
            PrefixedIds.isCorrelationId(prefixed(metadata.correlationId(), "corr-")) ? prefixed(metadata.correlationId(), "corr-") : PrefixedIds.newCorrelationId(),
            prefixedCausation(metadata.causationId()),
            PRODUCER,
            metadata.schemaVersion(),
            payload(event)
        );
    }

    public static Map<String, Object> caseDetails(PostSalesCase postSalesCase) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseId", prefixed(postSalesCase.caseId(), "psc-"));
        body.put("journeyOrderId", postSalesCase.journeyOrderId());
        body.put("caseType", postSalesCase.caseType().name());
        body.put("status", contractStatus(postSalesCase.status()));
        body.put("scope", postSalesCase.scope());
        body.put("reasonCode", postSalesCase.reasonCode());
        body.put("actorRef", postSalesCase.actorRef());
        if (postSalesCase.decision() != null) {
            body.put("decision", decisionDetails(postSalesCase.decision()));
        }
        if (postSalesCase.terminalReason() != null) {
            body.put("terminalReason", postSalesCase.terminalReason());
        }
        String createdAt = postSalesCase.domainEvents().stream().findFirst()
            .map(PostSalesEvent::metadata)
            .map(EventMetadata::occurredAt)
            .map(Object::toString)
            .orElse(null);
        body.put("createdAt", createdAt);
        return body;
    }

    public static Map<String, Object> openResponse(PostSalesCase postSalesCase) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseId", prefixed(postSalesCase.caseId(), "psc-"));
        body.put("journeyOrderId", postSalesCase.journeyOrderId());
        body.put("caseType", postSalesCase.caseType().name());
        body.put("status", contractStatus(postSalesCase.status()));
        body.put("createdAt", postSalesCase.domainEvents().getFirst().metadata().occurredAt().toString());
        return body;
    }

    public static Map<String, Object> evaluateResponse(PostSalesCase postSalesCase) {
        PostSalesDecision decision = postSalesCase.decision();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseId", prefixed(postSalesCase.caseId(), "psc-"));
        body.put("eligible", decision.eligible());
        body.put("adjustmentQuoteId", decision.ruleSnapshot().farePricingEvaluationRef());
        body.put("refundableAmount", money(decision.amountSnapshot().refundAmount()));
        body.put("amountDue", money(decision.amountSnapshot().extraChargeAmount()));
        return body;
    }

    public static Map<String, Object> approveResponse(PostSalesCase postSalesCase) {
        return Map.of("caseId", prefixed(postSalesCase.caseId(), "psc-"), "status", "APPROVED");
    }

    public static Map<String, Object> money(Money money) {
        return Map.of("currency", money.currency().getCurrencyCode(), "minorUnits", money.toMinorUnits());
    }

    public static String stripCasePrefix(String caseId) {
        return caseId != null && caseId.startsWith("psc-") ? caseId.substring(4) : caseId;
    }

    public static String prefixed(String value, String prefix) {
        if (value == null || value.startsWith(prefix)) {
            return value;
        }
        return prefix + value;
    }

    public static String contractStatus(PostSalesCaseStatus status) {
        return switch (status) {
            case ELIGIBILITY_CHECKING, QUOTED, PENDING_USER_CONFIRMATION, PENDING_APPROVAL -> "EVALUATING";
            case EXECUTING, COMPENSATION_PENDING, MANUAL_REVIEW_REQUIRED -> "APPROVED";
            case CANCELLED -> "REJECTED";
            default -> status.name();
        };
    }

    private static Object payload(PostSalesEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", prefixed(event.caseId(), "psc-"));
        if (event instanceof PostSalesCaseOpened opened) {
            payload.put("journeyOrderId", opened.journeyOrderId());
            payload.put("caseType", opened.caseType().name());
            payload.put("scope", opened.scope());
            payload.put("reasonCode", opened.reasonCode());
            payload.put("actorRef", opened.actorRef());
        } else if (event instanceof PostSalesRequested requested) {
            payload.put("orderId", requested.journeyOrderId());
            payload.put("requestType", requestType(requested.caseType()));
            payload.put("requestedAt", requested.metadata().occurredAt().toString());
        } else if (event instanceof PostSalesEvaluated evaluated) {
            payload.put("decisionKind", evaluated.decisionKind().name());
            payload.put("eligible", evaluated.eligible());
            payload.put("adjustmentQuoteId", evaluated.farePricingEvaluationRef());
        } else if (event instanceof PostSalesApproved approved) {
            payload.put("orderId", approved.journeyOrderId());
            payload.put("approvedActions", approvedActions(approved));
        } else if (event instanceof PostSalesRejected rejected) {
            payload.put("reason", rejected.reasonCode());
        } else if (event instanceof PostSalesApplied applied) {
            payload.put("orderId", applied.journeyOrderId());
            payload.put("resultSummary", resultSummary(applied));
        }
        return payload;
    }

    private static String requestType(PostSalesCaseType caseType) {
        return switch (caseType) {
            case CANCELLATION -> "CANCELLATION";
            case CHANGE, REBOOK -> "CHANGE";
            case REFUND, COMPENSATION -> "REFUND_BY_RULE";
        };
    }

    private static Map<String, Object> approvedActions(PostSalesApproved approved) {
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("decisionKind", approved.decisionKind().name());
        actions.put("approvalRef", approved.approvalRef());
        return actions;
    }

    private static Map<String, Object> resultSummary(PostSalesApplied applied) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("description", applied.resultSummary());
        return summary;
    }

    private static Map<String, Object> decisionDetails(PostSalesDecision decision) {
        AmountDecisionSnapshot amount = decision.amountSnapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", decision.version());
        body.put("kind", decision.kind().name());
        body.put("eligible", decision.eligible());
        body.put("reasonCode", decision.reasonCode());
        body.put("adjustmentQuoteId", decision.ruleSnapshot().farePricingEvaluationRef());
        body.put("refundableAmount", money(amount.refundAmount()));
        body.put("amountDue", money(amount.extraChargeAmount()));
        body.put("quotedAt", decision.quotedAt().toString());
        body.put("expiresAt", decision.expiresAt().toString());
        return body;
    }

    private static String prefixedCausation(String value) {
        String prefixed = value != null && (value.startsWith("cmd-") || value.startsWith("evt-")) ? value : prefixed(value, "cmd-");
        return PrefixedIds.isCausationId(prefixed) ? prefixed : PrefixedIds.newCommandId();
    }
}
