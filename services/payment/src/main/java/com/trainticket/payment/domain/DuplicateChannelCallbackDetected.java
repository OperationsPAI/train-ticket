package com.trainticket.payment.domain;

public record DuplicateChannelCallbackDetected(
    EventEnvelope envelope,
    String callbackRecordId,
    String channel,
    String callbackId,
    String firstCallbackRecordId
) implements PaymentEvent {}