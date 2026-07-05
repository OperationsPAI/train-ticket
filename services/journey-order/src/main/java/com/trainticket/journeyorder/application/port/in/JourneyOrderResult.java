package com.trainticket.journeyorder.application.port.in;

import java.time.Instant;
import java.util.List;

public record JourneyOrderResult(
    String orderId,
    String accountId,
    String offerId,
    MonetarySummaryDto monetarySummary,
    String status,
    List<String> travelerRefs,
    List<String> segmentRefs,
    Instant createdAt
) {
    public record MonetarySummaryDto(
        String currency,
        long subtotal,
        long taxTotal,
        long feeTotal,
        long discountTotal,
        long cancelledTotal,
        long payableTotal
    ) {}
}
