package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;

public record PaymentRefunded(
    EventEnvelope envelope,
    String paymentIntentId,
    String businessRef,
    String refundId,
    Money amount,
    ChannelRef channelRef
) implements PaymentEvent {
}
