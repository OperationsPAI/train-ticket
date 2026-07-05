package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record RefundSettled(
    EventEnvelope envelope,
    String refundId,
    String paymentIntentId,
    Money amount,
    String channelRefundTransactionId
) implements PaymentEvent {}
