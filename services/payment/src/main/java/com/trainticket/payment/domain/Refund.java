package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Refund {
    private final String refundId;
    private final String paymentIntentId;
    private final Money amount;
    private final String sourceCaseRef;
    private final String reasonCode;
    private final String idempotencyKey;
    private final List<PaymentEvent> domainEvents;
    private RefundStatus status;
    private String channelRefundTransactionId;
    private int attemptCount;
    private long version;

    private Refund(String refundId, String paymentIntentId, Money amount, String sourceCaseRef, String reasonCode, String idempotencyKey) {
        this.refundId = requireText(refundId, "refundId");
        this.paymentIntentId = requireText(paymentIntentId, "paymentIntentId");
        this.amount = Objects.requireNonNull(amount, "amount is required");
        if (amount.isZero()) {
            throw new DomainRuleViolation("refund amount must be positive");
        }
        this.sourceCaseRef = requireText(sourceCaseRef, "sourceCaseRef");
        this.reasonCode = requireText(reasonCode, "reasonCode");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.domainEvents = new ArrayList<>();
        this.status = RefundStatus.REQUESTED;
    }


    public static Refund rehydrate(
        String refundId,
        String paymentIntentId,
        Money amount,
        String sourceCaseRef,
        String reasonCode,
        String idempotencyKey,
        RefundStatus status,
        String channelRefundTransactionId,
        int attemptCount,
        List<PaymentEvent> domainEvents
    ) {
        Refund refund = new Refund(refundId, paymentIntentId, amount, sourceCaseRef, reasonCode, idempotencyKey);
        refund.status = Objects.requireNonNull(status, "status is required");
        refund.channelRefundTransactionId = channelRefundTransactionId;
        refund.attemptCount = attemptCount;
        refund.domainEvents.addAll(Objects.requireNonNull(domainEvents, "domainEvents are required"));
        return refund;
    }

    public Refund withVersion(long version) {
        if (version < 0) {
            throw new DomainRuleViolation("version must not be negative");
        }
        this.version = version;
        return this;
    }

    public static Refund request(
        PaymentIntent capturedIntent,
        Money amount,
        String sourceCaseRef,
        String reasonCode,
        String idempotencyKey,
        Instant occurredAt,
        String sourceCommandId,
        String correlationId
    ) {
        Objects.requireNonNull(capturedIntent, "capturedIntent is required");
        if (capturedIntent.status() != PaymentIntentStatus.CAPTURED) {
            throw new DomainRuleViolation("refund requires a captured payment intent");
        }
        if (amount.isGreaterThan(capturedIntent.refundableBalance())) {
            throw new DomainRuleViolation("refund amount cannot exceed captured-and-not-refunded balance");
        }
        Refund refund = new Refund("rf-" + UUID.randomUUID(), capturedIntent.paymentIntentId(), amount, sourceCaseRef, reasonCode, idempotencyKey);
        refund.domainEvents.add(new RefundRequested(
            createEnvelope("RefundRequested", occurredAt, sourceCommandId, correlationId),
            refund.refundId, refund.paymentIntentId, refund.amount, refund.sourceCaseRef, refund.reasonCode, refund.idempotencyKey
        ));
        return refund;
    }

    public String refundId() { return refundId; }
    public String paymentIntentId() { return paymentIntentId; }
    public Money amount() { return amount; }
    public String sourceCaseRef() { return sourceCaseRef; }
    public String reasonCode() { return reasonCode; }
    public String idempotencyKey() { return idempotencyKey; }
    public RefundStatus status() { return status; }
    public String channelRefundTransactionId() { return channelRefundTransactionId; }
    public int attemptCount() { return attemptCount; }
    public long version() { return version; }
    public List<PaymentEvent> domainEvents() { return List.copyOf(domainEvents); }

    public boolean semanticallyMatches(String paymentIntentId, Money amount, String sourceCaseRef, String reasonCode, String idempotencyKey) {
        return this.paymentIntentId.equals(paymentIntentId)
            && this.amount.equals(amount)
            && this.sourceCaseRef.equals(sourceCaseRef)
            && this.reasonCode.equals(reasonCode)
            && this.idempotencyKey.equals(idempotencyKey);
    }

    public void submitToChannel(String channelRefundTransactionId) {
        if (status != RefundStatus.REQUESTED && status != RefundStatus.FAILED) {
            throw new DomainRuleViolation("refund can only be submitted when requested or failed retryable");
        }
        this.channelRefundTransactionId = requireText(channelRefundTransactionId, "channelRefundTransactionId");
        this.attemptCount++;
        this.status = RefundStatus.SUBMITTED;
    }

    public void settle(PaymentIntent capturedIntent, String channelRefundTransactionId, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        Objects.requireNonNull(capturedIntent, "capturedIntent is required");
        if (status == RefundStatus.SETTLED) {
            return;
        }
        if (status != RefundStatus.SUBMITTED && status != RefundStatus.REQUESTED) {
            throw new DomainRuleViolation("refund can only settle from requested or submitted state");
        }
        if (!capturedIntent.paymentIntentId().equals(paymentIntentId)) {
            throw new DomainRuleViolation("refund settlement payment intent mismatch");
        }
        this.channelRefundTransactionId = requireText(channelRefundTransactionId, "channelRefundTransactionId");
        capturedIntent.markRefunded(amount);
        this.status = RefundStatus.SETTLED;
        domainEvents.add(new RefundSettled(
            createEnvelope("RefundSettled", occurredAt, causationId, correlationId),
            refundId, paymentIntentId, amount, this.channelRefundTransactionId
        ));
    }

    public void fail(String reasonCode, boolean retryable, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        if (status == RefundStatus.SETTLED) {
            throw new DomainRuleViolation("settled refund cannot fail");
        }
        if (status == RefundStatus.MANUAL_REVIEW_REQUIRED) {
            return;
        }
        status = retryable ? RefundStatus.FAILED : RefundStatus.MANUAL_REVIEW_REQUIRED;
        domainEvents.add(new RefundFailed(
            createEnvelope("RefundFailed", occurredAt, causationId, correlationId),
            refundId, paymentIntentId, requireText(reasonCode, "reasonCode")
        ));
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
    private static EventEnvelope createEnvelope(String eventType, Instant occurredAt, String causationId, String correlationId) {
        return new EventEnvelope(
            PrefixedIds.newEventId(),
            eventType,
            occurredAt,
            canonicalCorrelationId(correlationId),
            canonicalCausationId(causationId),
            "payment",
            1,
            java.util.Map.of()
        );
    }

    private static String canonicalCorrelationId(String correlationId) {
        return PrefixedIds.isCorrelationId(correlationId)
            ? correlationId
            : PrefixedIds.newCorrelationId();
    }

    private static String canonicalCausationId(String causationId) {
        if (PrefixedIds.isCausationId(causationId)) {
            return causationId;
        }
        return PrefixedIds.newCommandId();
    }

}
