package com.trainticket.financesettlement.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RevenueRecognition {
    private final String revenueRecognitionId;
    private final String orderId;
    private final String orderItemId;
    private final String componentCode;
    private final Money amount;
    private final String recognitionPolicyVersion;
    private final String sourceEventId;
    private final Instant recognizedAt;
    private final List<FinanceSettlementEvent> domainEvents;
    private boolean reversed;
    private String reversalReason;

    private RevenueRecognition(
        String revenueRecognitionId, String orderId, String orderItemId,
        String componentCode, Money amount, String recognitionPolicyVersion,
        String sourceEventId, Instant recognizedAt
    ) {
        this.revenueRecognitionId = requireText(revenueRecognitionId, "revenueRecognitionId");
        this.orderId = requireText(orderId, "orderId");
        this.orderItemId = requireText(orderItemId, "orderItemId");
        this.componentCode = requireText(componentCode, "componentCode");
        this.amount = Objects.requireNonNull(amount, "amount is required");
        this.recognitionPolicyVersion = requireText(recognitionPolicyVersion, "recognitionPolicyVersion");
        this.sourceEventId = requireText(sourceEventId, "sourceEventId");
        this.recognizedAt = Objects.requireNonNull(recognizedAt, "recognizedAt is required");
        this.domainEvents = new ArrayList<>();
        this.reversed = false;
        this.reversalReason = null;
    }

    public static RevenueRecognition recognize(
        String orderId, String orderItemId, String componentCode,
        Money amount, String recognitionPolicyVersion,
        String sourceEventId, Instant recognizedAt,
        Instant now, String sourceCommandId, String correlationId
    ) {
        if (amount.isNegative() && !("discount".equals(componentCode) || "refund".equals(componentCode)))
            throw new DomainRuleViolation("revenue recognition amount must not be negative for component: " + componentCode);
        if (amount.isZero())
            throw new DomainRuleViolation("revenue recognition amount must not be zero");

        RevenueRecognition recognition = new RevenueRecognition(
            com.trainticket.platformkit.idempotency.UuidV7.generate(), orderId, orderItemId, componentCode,
            amount, recognitionPolicyVersion, sourceEventId, recognizedAt
        );

        recognition.domainEvents.add(new RevenueRecognized(
            recognition.revenueRecognitionId, orderItemId, orderId, componentCode,
            amount, recognitionPolicyVersion, sourceEventId,
            EventMetadata.create(now, sourceCommandId, sourceCommandId, correlationId,
                Map.of("revenueRecognitionId", recognition.revenueRecognitionId, "componentCode", componentCode))
        ));
        return recognition;
    }

    public void reverse(String reason, Instant now, String sourceCommandId, String causationId, String correlationId) {
        if (reversed) return;
        this.reversed = true;
        this.reversalReason = requireText(reason, "reason");
        domainEvents.add(new RevenueRecognized(
            revenueRecognitionId, orderItemId, orderId, componentCode,
            amount.negate(), recognitionPolicyVersion + "-reversed", sourceEventId,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("revenueRecognitionId", revenueRecognitionId, "reversalReason", reason,
                       "originalSourceEventId", sourceEventId))
        ));
    }

    public String revenueRecognitionId() { return revenueRecognitionId; }
    public String orderId() { return orderId; }
    public String orderItemId() { return orderItemId; }
    public String componentCode() { return componentCode; }
    public Money amount() { return amount; }
    public String recognitionPolicyVersion() { return recognitionPolicyVersion; }
    public String sourceEventId() { return sourceEventId; }
    public Instant recognizedAt() { return recognizedAt; }
    public boolean reversed() { return reversed; }
    public String reversalReason() { return reversalReason; }
    public List<FinanceSettlementEvent> domainEvents() { return Collections.unmodifiableList(domainEvents); }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank())
            throw new DomainRuleViolation(name + " must not be blank");
        return value;
    }
}
