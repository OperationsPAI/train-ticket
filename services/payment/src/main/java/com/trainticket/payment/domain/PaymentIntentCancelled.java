package com.trainticket.payment.domain;

public record PaymentIntentCancelled(
    String paymentIntentId,
    String reason,
    EventMetadata metadata
) implements PaymentEvent {
    @Override
    public String eventType() {
        return "PaymentIntentCancelled";
    }
}
