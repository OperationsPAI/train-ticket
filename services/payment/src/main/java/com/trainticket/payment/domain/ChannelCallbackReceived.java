package com.trainticket.payment.domain;

public record ChannelCallbackReceived(
    EventEnvelope envelope,
    String callbackRecordId,
    String channel,
    String callbackId,
    String payloadDigest,
    CallbackProcessingStatus processingStatus
) implements PaymentEvent {}