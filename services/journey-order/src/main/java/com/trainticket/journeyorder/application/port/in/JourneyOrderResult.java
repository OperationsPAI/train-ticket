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
    ) {
        public MoneyDto subtotalMoney() { return new MoneyDto(currency, subtotal); }
        public MoneyDto taxTotalMoney() { return new MoneyDto(currency, taxTotal); }
        public MoneyDto feeTotalMoney() { return new MoneyDto(currency, feeTotal); }
        public MoneyDto discountTotalMoney() { return new MoneyDto(currency, discountTotal); }
        public MoneyDto cancelledTotalMoney() { return new MoneyDto(currency, cancelledTotal); }
        public MoneyDto payableTotalMoney() { return new MoneyDto(currency, payableTotal); }
    }

    public record MoneyDto(String currency, long minorUnits) {}
}
