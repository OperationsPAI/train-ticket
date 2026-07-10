package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record RefundSettled(
    EventEnvelope envelope,
    String refundId,
    String paymentIntentId,
    Money amount,
    String channelRefundTransactionId,
    ChannelRef channelRef
) implements PaymentEvent {
    public RefundSettled(EventEnvelope envelope, String refundId, String paymentIntentId, Money amount, String channelRefundTransactionId) {
        this(envelope, refundId, paymentIntentId, amount, channelRefundTransactionId, new ChannelRef(null, null, null, null, channelRefundTransactionId, null, null));
    }
}
