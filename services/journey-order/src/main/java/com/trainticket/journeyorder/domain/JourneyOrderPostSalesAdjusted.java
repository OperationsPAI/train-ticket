package com.trainticket.journeyorder.domain;

public record JourneyOrderPostSalesAdjusted(
    EventEnvelope envelope,
    String orderId,
    String accountId,
    String postSalesCaseId,
    MonetarySummary monetarySummary
) implements JourneyOrderEvent {}