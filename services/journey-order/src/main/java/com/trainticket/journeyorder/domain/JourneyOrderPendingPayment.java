package com.trainticket.journeyorder.domain;

public record JourneyOrderPendingPayment(
    EventEnvelope envelope,
    String orderId,
    String accountId,
    String paymentPurpose,
    MonetarySummary monetarySummary
) implements JourneyOrderEvent {}