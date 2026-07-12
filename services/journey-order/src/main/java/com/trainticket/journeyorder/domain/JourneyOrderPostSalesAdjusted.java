package com.trainticket.journeyorder.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record JourneyOrderPostSalesAdjusted(
    EventEnvelope envelope,
    String orderId,
    String accountId,
    String postSalesCaseId,
    MonetarySummary monetarySummary
) implements JourneyOrderEvent {}
