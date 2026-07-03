package com.trainticket.payment.domain;

public record DuplicateChannelCallbackDetected(
    String callbackRecordId,
    String channel,
    String callbackId,
    String firstCallbackRecordId,
    EventMetadata metadata
) implements PaymentEvent {
    @Override
    public String eventType() {
        return "DuplicateChannelCallbackDetected";
    }
}
