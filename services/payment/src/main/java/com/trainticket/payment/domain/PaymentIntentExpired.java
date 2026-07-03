package com.trainticket.payment.domain;

public record PaymentIntentExpired(
    String paymentIntentId,
    EventMetadata metadata
) implements PaymentEvent {
    @Override
    public String eventType() {
        return "PaymentIntentExpired";
    }
}
