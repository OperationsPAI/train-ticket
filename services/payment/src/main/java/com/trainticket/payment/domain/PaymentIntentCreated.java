package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record PaymentIntentCreated(
    EventEnvelope envelope,
    String paymentIntentId,
    String businessRef,
    String purpose,
    Money amount,
    String payerRef,
    String idempotencyKey
) implements PaymentEvent {}
