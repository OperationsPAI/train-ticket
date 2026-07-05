package com.trainticket.journeyorder.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public record CreateJourneyOrderResponse(
    @JsonProperty("orderId") String orderId,
    @JsonProperty("accountId") String accountId,
    @JsonProperty("offerId") String offerId,
    @JsonProperty("monetarySummary") MonetarySummaryDto monetarySummary,
    @JsonProperty("status") String status,
    @JsonProperty("travelerRefs") List<String> travelerRefs,
    @JsonProperty("segmentRefs") List<String> segmentRefs,
    @JsonProperty("createdAt") Instant createdAt
) {
    public record MonetarySummaryDto(
        @JsonProperty("currency") String currency,
        @JsonProperty("subtotal") long subtotal,
        @JsonProperty("taxTotal") long taxTotal,
        @JsonProperty("feeTotal") long feeTotal,
        @JsonProperty("discountTotal") long discountTotal,
        @JsonProperty("cancelledTotal") long cancelledTotal,
        @JsonProperty("payableTotal") long payableTotal
    ) {}
}
