package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record PaymentFailed(
    EventEnvelope envelope,
    String paymentIntentId,
    String businessRef,
    String reasonCode,
    boolean retryable,
    ChannelRef channelRef
) implements PaymentEvent {
    public PaymentFailed(EventEnvelope envelope, String paymentIntentId, String reasonCode, boolean retryable) {
        this(envelope, paymentIntentId, null, reasonCode, retryable, null);
    }
}
