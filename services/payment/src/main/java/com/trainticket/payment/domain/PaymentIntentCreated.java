package com.trainticket.payment.domain;

public record PaymentIntentCreated(
    EventEnvelope envelope,
    String paymentIntentId,
    String businessRef,
    String purpose,
    Money amount,
    String payerRef,
    String idempotencyKey
) implements PaymentEvent {}