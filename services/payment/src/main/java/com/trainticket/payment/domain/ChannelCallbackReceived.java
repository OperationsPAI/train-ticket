package com.trainticket.payment.domain;

public record ChannelCallbackReceived(
    String callbackRecordId,
    String channel,
    String callbackId,
    String payloadDigest,
    EventMetadata metadata
) implements PaymentEvent {
    @Override
    public String eventType() {
        return "ChannelCallbackReceived";
    }
}
