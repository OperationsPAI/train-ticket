package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class LatePaymentCase {
    private final String latePaymentCaseId;
    private final String paymentIntentId;
    private final Money capturedAmount;
    private final String channel;
    private final String channelTransactionId;
    private final String reason;
    private final Instant detectedAt;
    private final List<PaymentEvent> domainEvents;
    private LatePaymentCaseStatus status;
    private String resolutionNote;
    private long version;

    private LatePaymentCase(
        String latePaymentCaseId,
        String paymentIntentId,
        Money capturedAmount,
        String channel,
        String channelTransactionId,
        String reason,
        Instant detectedAt,
        List<PaymentEvent> domainEvents
    ) {
        this.latePaymentCaseId = requireText(latePaymentCaseId, "latePaymentCaseId");
        this.paymentIntentId = requireText(paymentIntentId, "paymentIntentId");
        this.capturedAmount = Objects.requireNonNull(capturedAmount, "capturedAmount is required");
        this.channel = requireText(channel, "channel");
        this.channelTransactionId = requireText(channelTransactionId, "channelTransactionId");
        this.reason = requireText(reason, "reason");
        this.detectedAt = Objects.requireNonNull(detectedAt, "detectedAt is required");
        this.domainEvents = new ArrayList<>(Objects.requireNonNull(domainEvents, "domainEvents are required"));
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
        String id = caseId(paymentIntentId, channel, channelTransactionId);
        LatePaymentDetected event = new LatePaymentDetected(
            createEnvelope("LatePaymentDetected", detectedAt, causationId, correlationId),
            id, paymentIntentId, capturedAmount, channel, channelTransactionId);
        return new LatePaymentCase(id, paymentIntentId, capturedAmount, channel, channelTransactionId, reason, detectedAt, List.of(event));
    }

    /**
     * Rebuilds a persisted case. Carries no new domain events: anything this case had to publish
     * was published by the transaction that opened it.
     */
    public static LatePaymentCase rehydrate(
        String latePaymentCaseId,
        String paymentIntentId,
        Money capturedAmount,
        String channel,
        String channelTransactionId,
        String reason,
        Instant detectedAt,
        LatePaymentCaseStatus status,
        String resolutionNote
    ) {
        LatePaymentCase lateCase = new LatePaymentCase(
            latePaymentCaseId, paymentIntentId, capturedAmount, channel, channelTransactionId, reason, detectedAt, List.of());
        lateCase.status = Objects.requireNonNull(status, "status is required");
        lateCase.resolutionNote = resolutionNote;
        return lateCase;
    }

    /**
     * Identity folded from the channel transaction rather than random, so the same late capture
     * always lands on the same case.
     *
     * A late capture can be reported more than once for one channel transaction — a
     * {@code ChannelOrderSucceeded} and the reconciliation-driven
     * {@code ChannelOrderRecoveryDetected} describe the same money under different event ids, so
     * consumer-side eventId dedup cannot collapse them. With a random id each report would open a
     * separate case for one payment, and the operator resolving one would leave the other open.
     */
    private static String caseId(String paymentIntentId, String channel, String channelTransactionId) {
        String material = requireText(paymentIntentId, "paymentIntentId")
            + ":" + requireText(channel, "channel")
            + ":" + requireText(channelTransactionId, "channelTransactionId");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("lpc-");
            for (int index = 0; index < 16; index++) {
                hex.append(String.format("%02x", digest[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
    public List<PaymentEvent> domainEvents() { return List.copyOf(domainEvents); }
    public long version() { return version; }

    public LatePaymentCase withVersion(long version) {
        if (version < 0) {
            throw new DomainRuleViolation("version must not be negative");
        }
        this.version = version;
        return this;
    }

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
