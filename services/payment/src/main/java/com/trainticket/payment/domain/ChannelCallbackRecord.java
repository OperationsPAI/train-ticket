package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ChannelCallbackRecord {
    private final String callbackRecordId;
    private final String channel;
    private final String callbackId;
    private final String payloadDigest;
    private final String callbackType;
    private final Instant receivedAt;
    private final PaymentEvent auditEvent;
    private CallbackProcessingStatus status;
    private String firstCallbackRecordId;

    private ChannelCallbackRecord(
        String callbackRecordId,
        String channel,
        String callbackId,
        String payloadDigest,
        String callbackType,
        Instant receivedAt,
        CallbackProcessingStatus status,
        String firstCallbackRecordId,
        PaymentEvent auditEvent
    ) {
        this.callbackRecordId = requireText(callbackRecordId, "callbackRecordId");
        this.channel = requireText(channel, "channel");
        this.callbackId = requireText(callbackId, "callbackId");
        this.payloadDigest = requireText(payloadDigest, "payloadDigest");
        this.callbackType = requireText(callbackType, "callbackType");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt is required");
        this.status = Objects.requireNonNull(status, "status is required");
        this.firstCallbackRecordId = firstCallbackRecordId;
        this.auditEvent = Objects.requireNonNull(auditEvent, "auditEvent is required");
    }

    public static ChannelCallbackRecord receive(
        String channel,
        String callbackId,
        String payloadDigest,
        String callbackType,
        List<ChannelCallbackRecord> existingRecords,
        Instant receivedAt,
        String sourceCommandId,
        String correlationId
    ) {
        Objects.requireNonNull(existingRecords, "existingRecords are required");
        String id = UUID.randomUUID().toString();
        return existingRecords.stream()
            .filter(record -> record.hasSameIdempotencyKey(channel, callbackId, payloadDigest))
            .findFirst()
            .map(first -> new ChannelCallbackRecord(id, channel, callbackId, payloadDigest, callbackType, receivedAt, CallbackProcessingStatus.DUPLICATE, first.callbackRecordId,
                new DuplicateChannelCallbackDetected(
                    createEnvelope("DuplicateChannelCallbackDetected", receivedAt, sourceCommandId, correlationId),
                    id, channel, callbackId, first.callbackRecordId)))
            .orElseGet(() -> new ChannelCallbackRecord(id, channel, callbackId, payloadDigest, callbackType, receivedAt, CallbackProcessingStatus.RECEIVED, null,
                new ChannelCallbackReceived(
                    createEnvelope("ChannelCallbackReceived", receivedAt, sourceCommandId, correlationId),
                    id, channel, callbackId, payloadDigest, CallbackProcessingStatus.RECEIVED)));
    }

    public String callbackRecordId() { return callbackRecordId; }
    public String channel() { return channel; }
    public String callbackId() { return callbackId; }
    public String payloadDigest() { return payloadDigest; }
    public String callbackType() { return callbackType; }
    public Instant receivedAt() { return receivedAt; }
    public CallbackProcessingStatus status() { return status; }
    public String firstCallbackRecordId() { return firstCallbackRecordId; }
    public PaymentEvent auditEvent() { return auditEvent; }

    public boolean isDuplicate() {
        return status == CallbackProcessingStatus.DUPLICATE;
    }

    public void markApplied() {
        if (status == CallbackProcessingStatus.DUPLICATE || status == CallbackProcessingStatus.REJECTED) {
            throw new DomainRuleViolation("duplicate or rejected callbacks cannot be applied");
        }
        status = CallbackProcessingStatus.APPLIED;
    }

    public boolean hasSameIdempotencyKey(String channel, String callbackId, String payloadDigest) {
        return this.channel.equals(requireText(channel, "channel"))
            && this.callbackId.equals(requireText(callbackId, "callbackId"))
            && this.payloadDigest.equals(requireText(payloadDigest, "payloadDigest"));
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
