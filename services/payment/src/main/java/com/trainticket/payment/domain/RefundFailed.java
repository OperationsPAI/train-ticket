package com.trainticket.payment.domain;

public record RefundFailed(
    EventEnvelope envelope,
    String refundId,
    String paymentIntentId,
    String reasonCode,
    boolean retryable
) implements PaymentEvent {}