package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Map<String, RefundRecord> refundHistory;
    private final List<PaymentEvent> domainEvents;
    private ChannelRef channelRef;
    private String channelOrderIdempotencyKey;
    private String channelOrderSubmitIdempotencyKey;
    private PaymentIntentStatus status;
    private Money authorizedAmount;
    private Money capturedAmount;
    private Money refundedAmount;
    private long version;

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
        this.refundHistory = new LinkedHashMap<>();
        this.domainEvents = new ArrayList<>();
        this.status = PaymentIntentStatus.CREATED;
        this.authorizedAmount = Money.zero(amount.currency());
        this.capturedAmount = Money.zero(amount.currency());
        this.refundedAmount = Money.zero(amount.currency());
    }


    public static PaymentIntent rehydrate(
        String paymentIntentId,
        String businessRef,
        String purpose,
        Money amount,
        String payerRef,
        Instant expiresAt,
        String idempotencyKey,
        PaymentIntentStatus status,
        Money authorizedAmount,
        Money capturedAmount,
        Money refundedAmount,
        Set<String> channelTransactionRefs,
        List<PaymentEvent> domainEvents
    ) {
        PaymentIntent intent = new PaymentIntent(paymentIntentId, businessRef, purpose, amount, payerRef, expiresAt, idempotencyKey);
        intent.status = Objects.requireNonNull(status, "status is required");
        intent.authorizedAmount = Objects.requireNonNull(authorizedAmount, "authorizedAmount is required");
        intent.capturedAmount = Objects.requireNonNull(capturedAmount, "capturedAmount is required");
        intent.refundedAmount = Objects.requireNonNull(refundedAmount, "refundedAmount is required");
        intent.channelTransactionRefs.addAll(Objects.requireNonNull(channelTransactionRefs, "channelTransactionRefs are required"));
        intent.domainEvents.addAll(Objects.requireNonNull(domainEvents, "domainEvents are required"));
        return intent;
    }

    public static PaymentIntent rehydrate(
        String paymentIntentId,
        String businessRef,
        String purpose,
        Money amount,
        String payerRef,
        Instant expiresAt,
        String idempotencyKey,
        PaymentIntentStatus status,
        Money authorizedAmount,
        Money capturedAmount,
        Money refundedAmount,
        Set<String> channelTransactionRefs,
        List<PaymentEvent> domainEvents,
        ChannelRef channelRef
    ) {
        return rehydrate(paymentIntentId, businessRef, purpose, amount, payerRef, expiresAt, idempotencyKey, status, authorizedAmount, capturedAmount, refundedAmount, channelTransactionRefs, domainEvents, channelRef, null, null);
    }

    public static PaymentIntent rehydrate(
        String paymentIntentId,
        String businessRef,
        String purpose,
        Money amount,
        String payerRef,
        Instant expiresAt,
        String idempotencyKey,
        PaymentIntentStatus status,
        Money authorizedAmount,
        Money capturedAmount,
        Money refundedAmount,
        Set<String> channelTransactionRefs,
        List<PaymentEvent> domainEvents,
        ChannelRef channelRef,
        String channelOrderIdempotencyKey,
        String channelOrderSubmitIdempotencyKey
    ) {
        PaymentIntent intent = rehydrate(paymentIntentId, businessRef, purpose, amount, payerRef, expiresAt, idempotencyKey, status, authorizedAmount, capturedAmount, refundedAmount, channelTransactionRefs, domainEvents);
        intent.channelRef = channelRef;
        intent.channelOrderIdempotencyKey = blankToNull(channelOrderIdempotencyKey);
        intent.channelOrderSubmitIdempotencyKey = blankToNull(channelOrderSubmitIdempotencyKey);
        return intent;
    }

    public static PaymentIntent rehydrate(
        String paymentIntentId,
        String businessRef,
        String purpose,
        Money amount,
        String payerRef,
        Instant expiresAt,
        String idempotencyKey,
        PaymentIntentStatus status,
        Money authorizedAmount,
        Money capturedAmount,
        Money refundedAmount,
        Set<String> channelTransactionRefs,
        List<PaymentEvent> domainEvents,
        ChannelRef channelRef,
        String channelOrderIdempotencyKey,
        String channelOrderSubmitIdempotencyKey,
        List<RefundRecord> refundHistory
    ) {
        PaymentIntent intent = rehydrate(paymentIntentId, businessRef, purpose, amount, payerRef, expiresAt, idempotencyKey, status, authorizedAmount, capturedAmount, refundedAmount, channelTransactionRefs, domainEvents, channelRef, channelOrderIdempotencyKey, channelOrderSubmitIdempotencyKey);
        for (RefundRecord record : Objects.requireNonNull(refundHistory, "refundHistory is required")) {
            intent.refundHistory.put(record.refundId(), record);
        }
        return intent;
    }

    public PaymentIntent withVersion(long version) {
        if (version < 0) {
            throw new DomainRuleViolation("version must not be negative");
        }
        this.version = version;
        return this;
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
            createEnvelope("PaymentIntentCreated", occurredAt, sourceCommandId, correlationId),
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
    public long version() { return version; }
    public List<PaymentEvent> domainEvents() { return List.copyOf(domainEvents); }
    public Set<String> channelTransactionRefs() { return Set.copyOf(channelTransactionRefs); }
    public List<RefundRecord> refundHistory() { return List.copyOf(refundHistory.values()); }
    public ChannelRef channelRef() { return channelRef; }
    public String channelOrderIdempotencyKey() { return channelOrderIdempotencyKey; }
    public String channelOrderSubmitIdempotencyKey() { return channelOrderSubmitIdempotencyKey; }

    public void rememberChannelOrderKeys(String orderKey, String submitKey) {
        requireNonTerminalForPayment("handoff payment capture");
        String normalizedOrderKey = requireText(orderKey, "channelOrderIdempotencyKey");
        String normalizedSubmitKey = requireText(submitKey, "channelOrderSubmitIdempotencyKey");
        if (channelOrderIdempotencyKey != null && !channelOrderIdempotencyKey.equals(normalizedOrderKey)) {
            throw new DomainRuleViolation("channel order idempotency key already assigned");
        }
        if (channelOrderSubmitIdempotencyKey != null && !channelOrderSubmitIdempotencyKey.equals(normalizedSubmitKey)) {
            throw new DomainRuleViolation("channel order submit idempotency key already assigned");
        }
        this.channelOrderIdempotencyKey = normalizedOrderKey;
        this.channelOrderSubmitIdempotencyKey = normalizedSubmitKey;
    }

    public boolean semanticallyMatches(String businessRef, String purpose, Money amount, String payerRef, Instant expiresAt, String idempotencyKey) {
        return this.businessRef.equals(businessRef)
            && this.purpose.equals(purpose)
            && this.amount.equals(amount)
            && this.payerRef.equals(payerRef)
            && this.expiresAt.equals(expiresAt)
            && this.idempotencyKey.equals(idempotencyKey);
    }

    public void recordChannelHandoff(ChannelRef ref) {
        requireNonTerminalForPayment("handoff payment capture");
        ChannelRef next = Objects.requireNonNull(ref, "channelRef is required");
        if (this.channelRef != null) {
            next = new ChannelRef(
                next.channel() == null ? this.channelRef.channel() : next.channel(),
                next.channelOrderId() == null ? this.channelRef.channelOrderId() : next.channelOrderId(),
                next.channelRefundId() == null ? this.channelRef.channelRefundId() : next.channelRefundId(),
                next.channelTransactionId() == null ? this.channelRef.channelTransactionId() : next.channelTransactionId(),
                next.channelRefundTransactionId() == null ? this.channelRef.channelRefundTransactionId() : next.channelRefundTransactionId(),
                next.channelStatementId() == null ? this.channelRef.channelStatementId() : next.channelStatementId(),
                next.faultSeedRef() == null ? this.channelRef.faultSeedRef() : next.faultSeedRef()
            );
        }
        this.channelRef = next;
    }

    public void authorize(Money authorizedAmount, String channel, String channelTransactionId, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireNonTerminalForPayment("authorize payment");
        requireNotExpiredAt(occurredAt);
        requirePositiveWithinIntent(authorizedAmount, "authorizedAmount");
        this.authorizedAmount = authorizedAmount;
        this.status = PaymentIntentStatus.AUTHORIZED;
        this.channelTransactionRefs.add(channelTransactionKey(channel, channelTransactionId));
        domainEvents.add(new PaymentAuthorized(
            createEnvelope("PaymentAuthorized", occurredAt, causationId, correlationId),
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
            createEnvelope("PaymentFailed", occurredAt, causationId, correlationId),
            paymentIntentId, businessRef, requireText(reasonCode, "reasonCode"), retryable, channelRef
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
            createEnvelope("PaymentIntentCancelled", occurredAt, causationId, correlationId),
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
            createEnvelope("PaymentIntentExpired", occurredAt, causationId, correlationId),
            paymentIntentId
        ));
        domainEvents.add(new PaymentTimedOut(
            createEnvelope("PaymentTimedOut", occurredAt, causationId, correlationId),
            paymentIntentId, businessRef, expiresAt, "TIMEOUT"
        ));
    }

    void markRefunded(Money amount) {
        refundedAmount = refundedAmount.add(amount);
        if (refundedAmount.isGreaterThan(capturedAmount)) {
            throw new DomainRuleViolation("refunded amount cannot exceed captured amount");
        }
    }

    void recordRefund(Refund refund, ChannelRef refundChannelRef, Instant createdAt) {
        Objects.requireNonNull(refund, "refund is required");
        RefundRecord record = new RefundRecord(refund.refundId(), refund.amount(), refundChannelRef, refund.status(), createdAt);
        refundHistory.put(record.refundId(), record);
        Money total = Money.zero(capturedAmount.currency());
        for (RefundRecord entry : refundHistory.values()) {
            total = total.add(entry.amount());
        }
        if (total.isGreaterThan(capturedAmount)) {
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
        this.channelRef = (this.channelRef == null ? new ChannelRef(channel, null, null, channelTransactionId, null, null, null) : this.channelRef.withTransaction(channelTransactionId));
        domainEvents.add(new PaymentCaptured(
            createEnvelope("PaymentCaptured", occurredAt, causationId, correlationId),
            paymentIntentId, businessRef, captureAmount, requireText(channel, "channel"), requireText(channelTransactionId, "channelTransactionId"), this.channelRef
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
