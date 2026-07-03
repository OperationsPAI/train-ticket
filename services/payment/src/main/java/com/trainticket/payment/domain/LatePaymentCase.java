package com.trainticket.payment.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class LatePaymentCase {
    private final String latePaymentCaseId;
    private final String paymentIntentId;
    private final Money capturedAmount;
    private final String channel;
    private final String channelTransactionId;
    private final String reason;
    private final Instant detectedAt;
    private final PaymentEvent domainEvent;
    private LatePaymentCaseStatus status;
    private String resolutionNote;

    private LatePaymentCase(
        String latePaymentCaseId,
        String paymentIntentId,
        Money capturedAmount,
        String channel,
        String channelTransactionId,
        String reason,
        Instant detectedAt,
        PaymentEvent domainEvent
    ) {
        this.latePaymentCaseId = requireText(latePaymentCaseId, "latePaymentCaseId");
        this.paymentIntentId = requireText(paymentIntentId, "paymentIntentId");
        this.capturedAmount = Objects.requireNonNull(capturedAmount, "capturedAmount is required");
        this.channel = requireText(channel, "channel");
        this.channelTransactionId = requireText(channelTransactionId, "channelTransactionId");
        this.reason = requireText(reason, "reason");
        this.detectedAt = Objects.requireNonNull(detectedAt, "detectedAt is required");
        this.domainEvent = Objects.requireNonNull(domainEvent, "domainEvent is required");
        this.status = LatePaymentCaseStatus.OPEN;
    }

    static LatePaymentCase open(
        String paymentIntentId,
        Money capturedAmount,
        String channel,
        String channelTransactionId,
        String reason,
        Instant detectedAt,
        String sourceCommandId,
        String causationId,
        String correlationId
    ) {
        String id = UUID.randomUUID().toString();
        LatePaymentDetected event = new LatePaymentDetected(id, paymentIntentId, capturedAmount, channelTransactionId, reason,
            EventMetadata.create(detectedAt, sourceCommandId, causationId, correlationId, Map.of("status", LatePaymentCaseStatus.OPEN.name(), "channel", channel)));
        return new LatePaymentCase(id, paymentIntentId, capturedAmount, channel, channelTransactionId, reason, detectedAt, event);
    }

    public String latePaymentCaseId() { return latePaymentCaseId; }
    public String paymentIntentId() { return paymentIntentId; }
    public Money capturedAmount() { return capturedAmount; }
    public String channel() { return channel; }
    public String channelTransactionId() { return channelTransactionId; }
    public String reason() { return reason; }
    public Instant detectedAt() { return detectedAt; }
    public LatePaymentCaseStatus status() { return status; }
    public String resolutionNote() { return resolutionNote; }
    public List<PaymentEvent> domainEvents() { return List.of(domainEvent); }

    public void resolve(String resolutionNote) {
        if (status == LatePaymentCaseStatus.RESOLVED) {
            return;
        }
        this.resolutionNote = requireText(resolutionNote, "resolutionNote");
        this.status = LatePaymentCaseStatus.RESOLVED;
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
