package com.trainticket.payment.domain;

public record RefundRequested(
    EventEnvelope envelope,
    String refundId,
    String paymentIntentId,
    Money amount,
    String sourceCaseRef,
    String reasonCode,
    String idempotencyKey
) implements PaymentEvent {}