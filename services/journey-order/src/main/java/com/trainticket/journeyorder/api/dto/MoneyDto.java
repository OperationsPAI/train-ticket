package com.trainticket.journeyorder.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MoneyDto(
    @JsonProperty("currency") String currency,
    @JsonProperty("minorUnits") long minorUnits
) {
}
