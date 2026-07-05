package com.trainticket.journeyorder.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ListJourneyOrdersResponse(
    @JsonProperty("items") List<CreateJourneyOrderResponse> items,
    @JsonProperty("total") int total,
    @JsonProperty("limit") int limit,
    @JsonProperty("offset") int offset
) {}
