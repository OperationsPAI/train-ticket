package com.trainticket.journeyorder.domain;

public sealed interface JourneyOrderEvent permits
    JourneyOrderCreated,
    JourneyOrderPendingPayment,
    JourneyOrderConfirmed,
    JourneyOrderCancelled,
    JourneyOrderPostSalesAdjusted,
    JourneyOrderPaymentRecorded {
    EventEnvelope envelope();
    String orderId();
    String accountId();
}
