package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record PaymentCaptured(
    EventEnvelope envelope,
    String paymentIntentId,
    String businessRef,
    Money capturedAmount,
    String channel,
    String channelTransactionId,
    ChannelRef channelRef
) implements PaymentEvent {
    public PaymentCaptured(EventEnvelope envelope, String paymentIntentId, String businessRef, Money capturedAmount, String channel, String channelTransactionId) {
        this(envelope, paymentIntentId, businessRef, capturedAmount, channel, channelTransactionId, new ChannelRef(channel, null, null, channelTransactionId, null, null, null));
    }
}
