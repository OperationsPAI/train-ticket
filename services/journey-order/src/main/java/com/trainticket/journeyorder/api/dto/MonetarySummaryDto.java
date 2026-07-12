package com.trainticket.journeyorder.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MonetarySummaryDto(
    @JsonProperty("subtotal") MoneyDto subtotal,
    @JsonProperty("taxTotal") MoneyDto taxTotal,
    @JsonProperty("feeTotal") MoneyDto feeTotal,
    @JsonProperty("discountTotal") MoneyDto discountTotal,
    @JsonProperty("cancelledTotal") MoneyDto cancelledTotal,
    @JsonProperty("payableTotal") MoneyDto payableTotal,
    @JsonProperty("total") MoneyDto total,
    @JsonProperty("currency") String currency
) {
}
