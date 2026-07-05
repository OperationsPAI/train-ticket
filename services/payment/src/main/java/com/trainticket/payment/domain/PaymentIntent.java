package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.EnvelopeFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class PaymentIntent {
    private final String paymentIntentId;
    private final String businessRef;
    private final String purpose;
    private final Money amount;
    private final String payerRef;
    private final Instant expiresAt;
    private final String idempotencyKey;
    private final Set<String> channelTransactionRefs;
    private final List<PaymentEvent> domainEvents;
    private PaymentIntentStatus status;
    private Money authorizedAmount;
    private Money capturedAmount;
    private Money refundedAmount;

    private PaymentIntent(
        String paymentIntentId,
        String businessRef,
        String purpose,
        Money amount,
        String payerRef,
        Instant expiresAt,
        String idempotencyKey
    ) {
        this.paymentIntentId = requireText(paymentIntentId, "paymentIntentId");
        this.businessRef = requireText(businessRef, "businessRef");
        this.purpose = requireText(purpose, "purpose");
        this.amount = Objects.requireNonNull(amount, "amount is required");
        if (amount.isZero()) {
            throw new DomainRuleViolation("payment intent amount must be positive");
        }
        this.payerRef = requireText(payerRef, "payerRef");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt is required");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.channelTransactionRefs = new HashSet<>();
        this.domainEvents = new ArrayList<>();
        this.status = PaymentIntentStatus.CREATED;
        this.authorizedAmount = Money.zero(amount.currency());
        this.capturedAmount = Money.zero(amount.currency());
        this.refundedAmount = Money.zero(amount.currency());
    }

    public static PaymentIntent create(
        String businessRef,
        String purpose,
        Money amount,
        String payerRef,
        Instant expiresAt,
        String idempotencyKey,
        Instant occurredAt,
        String sourceCommandId,
        String correlationId
    ) {
        if (!Objects.requireNonNull(expiresAt, "expiresAt is required").isAfter(Objects.requireNonNull(occurredAt, "occurredAt is required"))) {
            throw new DomainRuleViolation("payment intent expiry must be in the future");
        }
        PaymentIntent intent = new PaymentIntent("pi-" + UUID.randomUUID(), businessRef, purpose, amount, payerRef, expiresAt, idempotencyKey);
        intent.domainEvents.add(new PaymentIntentCreated(
            EnvelopeFactory.create("PaymentIntentCreated", occurredAt, sourceCommandId, correlationId, "payment"),
            intent.paymentIntentId, intent.businessRef, intent.purpose, intent.amount, intent.payerRef, intent.idempotencyKey
        ));
        return intent;
    }

    public String paymentIntentId() { return paymentIntentId; }
    public String businessRef() { return businessRef; }
    public String purpose() { return purpose; }
    public Money amount() { return amount; }
    public String payerRef() { return payerRef; }
    public Instant expiresAt() { return expiresAt; }
    public String idempotencyKey() { return idempotencyKey; }
    public PaymentIntentStatus status() { return status; }
    public Money authorizedAmount() { return authorizedAmount; }
    public Money capturedAmount() { return capturedAmount; }
    public Money refundedAmount() { return refundedAmount; }
    public List<PaymentEvent> domainEvents() { return List.copyOf(domainEvents); }
    public Set<String> channelTransactionRefs() { return Set.copyOf(channelTransactionRefs); }

    public boolean semanticallyMatches(String businessRef, String purpose, Money amount, String payerRef, Instant expiresAt, String idempotencyKey) {
        return this.businessRef.equals(businessRef)
            && this.purpose.equals(purpose)
            && this.amount.equals(amount)
            && this.payerRef.equals(payerRef)
            && this.expiresAt.equals(expiresAt)
            && this.idempotencyKey.equals(idempotencyKey);
    }

    public void authorize(Money authorizedAmount, String channel, String channelTransactionId, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireNonTerminalForPayment("authorize payment");
        requireNotExpiredAt(occurredAt);
        requirePositiveWithinIntent(authorizedAmount, "authorizedAmount");
        this.authorizedAmount = authorizedAmount;
        this.status = PaymentIntentStatus.AUTHORIZED;
        this.channelTransactionRefs.add(channelTransactionKey(channel, channelTransactionId));
        domainEvents.add(new PaymentAuthorized(
            EnvelopeFactory.create("PaymentAuthorized", occurredAt, causationId, correlationId, "payment"),
            paymentIntentId, authorizedAmount, requireText(channel, "channel"), requireText(channelTransactionId, "channelTransactionId")
        ));
    }

    public void capture(Money captureAmount, String channel, String channelTransactionId, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireNonTerminalForPayment("capture payment");
        requireNotExpiredAt(occurredAt);
        applyCapture(captureAmount, channel, channelTransactionId, occurredAt, sourceCommandId, causationId, correlationId);
    }

    public LatePaymentCase recordLateCapture(Money captureAmount, String channel, String channelTransactionId, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        if (status != PaymentIntentStatus.CANCELLED && status != PaymentIntentStatus.EXPIRED) {
            throw new DomainRuleViolation("late capture is only valid for cancelled or expired payment intents");
        }
        return LatePaymentCase.open(paymentIntentId, captureAmount, channel, channelTransactionId,
            "capture arrived after " + status.name().toLowerCase(), occurredAt, sourceCommandId, causationId, correlationId);
    }

    public void fail(String reasonCode, boolean retryable, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        if (status == PaymentIntentStatus.CAPTURED) {
            throw new DomainRuleViolation("captured payment intent cannot fail");
        }
        if (isTerminal()) {
            return;
        }
        status = PaymentIntentStatus.FAILED;
        domainEvents.add(new PaymentFailed(
            EnvelopeFactory.create("PaymentFailed", occurredAt, causationId, correlationId, "payment"),
            paymentIntentId, requireText(reasonCode, "reasonCode"), retryable
        ));
    }

    public void cancel(String reason, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        if (status == PaymentIntentStatus.CAPTURED) {
            throw new DomainRuleViolation("captured payment intent cannot be cancelled; request refund from upstream decision instead");
        }
        if (isTerminal()) {
            return;
        }
        status = PaymentIntentStatus.CANCELLED;
        domainEvents.add(new PaymentIntentCancelled(
            EnvelopeFactory.create("PaymentIntentCancelled", occurredAt, causationId, correlationId, "payment"),
            paymentIntentId, requireText(reason, "reason")
        ));
    }

    public void expire(Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        if (status == PaymentIntentStatus.CAPTURED) {
            throw new DomainRuleViolation("captured payment intent cannot expire");
        }
        if (isTerminal()) {
            return;
        }
        if (occurredAt.isBefore(expiresAt)) {
            throw new DomainRuleViolation("cannot expire payment intent before expiresAt");
        }
        status = PaymentIntentStatus.EXPIRED;
        domainEvents.add(new PaymentIntentExpired(
            EnvelopeFactory.create("PaymentIntentExpired", occurredAt, causationId, correlationId, "payment"),
            paymentIntentId
        ));
    }

    void markRefunded(Money amount) {
        refundedAmount = refundedAmount.add(amount);
        if (refundedAmount.isGreaterThan(capturedAmount)) {
            throw new DomainRuleViolation("refunded amount cannot exceed captured amount");
        }
    }

    public Money refundableBalance() {
        return capturedAmount.subtract(refundedAmount);
    }

    private void applyCapture(Money captureAmount, String channel, String channelTransactionId, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requirePositiveWithinIntent(captureAmount, "captureAmount");
        if (capturedAmount.add(captureAmount).isGreaterThan(amount)) {
            throw new DomainRuleViolation("capture amount cannot exceed payment intent amount");
        }
        if (!authorizedAmount.isZero() && capturedAmount.add(captureAmount).isGreaterThan(authorizedAmount)) {
            throw new DomainRuleViolation("capture amount cannot exceed authorized amount");
        }
        this.capturedAmount = capturedAmount.add(captureAmount);
        this.status = capturedAmount.equals(amount) ? PaymentIntentStatus.CAPTURED : status;
        this.channelTransactionRefs.add(channelTransactionKey(channel, channelTransactionId));
        domainEvents.add(new PaymentCaptured(
            EnvelopeFactory.create("PaymentCaptured", occurredAt, causationId, correlationId, "payment"),
            paymentIntentId, captureAmount, requireText(channel, "channel"), requireText(channelTransactionId, "channelTransactionId")
        ));
    }

    private void requireNonTerminalForPayment(String action) {
        if (isTerminal()) {
            throw new DomainRuleViolation("cannot " + action + " when payment intent is " + status);
        }
    }

    private boolean isTerminal() {
        return status == PaymentIntentStatus.CAPTURED || status == PaymentIntentStatus.CANCELLED || status == PaymentIntentStatus.EXPIRED || status == PaymentIntentStatus.FAILED;
    }

    private void requireNotExpiredAt(Instant occurredAt) {
        if (!Objects.requireNonNull(occurredAt, "occurredAt is required").isBefore(expiresAt)) {
            throw new DomainRuleViolation("payment intent is expired for new payment activity");
        }
    }

    private void requirePositiveWithinIntent(Money value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isZero()) {
            throw new DomainRuleViolation(name + " must be positive");
        }
        if (value.isGreaterThan(amount)) {
            throw new DomainRuleViolation(name + " cannot exceed payment intent amount");
        }
    }

    private static String channelTransactionKey(String channel, String channelTransactionId) {
        return requireText(channel, "channel") + ":" + requireText(channelTransactionId, "channelTransactionId");
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
