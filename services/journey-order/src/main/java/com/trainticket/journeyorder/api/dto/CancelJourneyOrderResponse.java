package com.trainticket.journeyorder.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record CancelJourneyOrderResponse(
    @JsonProperty("orderId") String orderId,
    @JsonProperty("status") String status,
    @JsonProperty("cancelledAt") Instant cancelledAt
) {}
