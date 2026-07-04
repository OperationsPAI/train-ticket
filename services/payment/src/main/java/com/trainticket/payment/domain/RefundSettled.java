package com.trainticket.payment.domain;

public record RefundSettled(
    EventEnvelope envelope,
    String refundId,
    String paymentIntentId,
    Money amount,
    String channelRefundTransactionId
) implements PaymentEvent {}