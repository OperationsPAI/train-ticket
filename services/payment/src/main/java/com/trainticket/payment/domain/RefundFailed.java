package com.trainticket.payment.domain;

public record RefundFailed(
    EventEnvelope envelope,
    String refundId,
    String paymentIntentId,
    String reason
) implements PaymentEvent {}
