package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record ChannelCallbackReceived(
    EventEnvelope envelope,
    String callbackRecordId,
    String channel,
    String callbackId,
    String payloadDigest,
    CallbackProcessingStatus processingStatus
) implements PaymentEvent {}
