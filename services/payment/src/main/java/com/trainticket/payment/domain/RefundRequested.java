package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record RefundRequested(
    EventEnvelope envelope,
    String refundId,
    String paymentIntentId,
    Money amount,
    String sourceCaseRef,
    String reasonCode,
    String idempotencyKey
) implements PaymentEvent {}
