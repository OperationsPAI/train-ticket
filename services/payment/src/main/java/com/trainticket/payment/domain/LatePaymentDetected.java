package com.trainticket.payment.domain;

public record LatePaymentDetected(
    String latePaymentCaseId,
    String paymentIntentId,
    Money capturedAmount,
    String channelTransactionId,
    String reason,
    EventMetadata metadata
) implements PaymentEvent {
    @Override
    public String eventType() {
        return "LatePaymentDetected";
    }
}
