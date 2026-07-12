package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record RefundFailed(
    EventEnvelope envelope,
    String refundId,
    String paymentIntentId,
    String reason
) implements PaymentEvent {}
