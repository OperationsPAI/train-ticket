package com.trainticket.journeyorder.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public record GetJourneyOrderResponse(
    @JsonProperty("orderId") String orderId,
    @JsonProperty("accountId") String accountId,
    @JsonProperty("offerId") String offerId,
    @JsonProperty("monetarySummary") MonetarySummaryDto monetarySummary,
    @JsonProperty("status") String status,
    @JsonProperty("travelerRefs") List<String> travelerRefs,
    @JsonProperty("segmentRefs") List<String> segmentRefs,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("riskClear") boolean riskClear,
    @JsonProperty("cancellationReason") String cancellationReason,
    @JsonProperty("travelers") List<com.trainticket.journeyorder.domain.TravelerRef> travelers,
    @JsonProperty("segments") List<com.trainticket.journeyorder.domain.SegmentOrderSnapshot> segments,
    @JsonProperty("ancillaryItems") List<com.trainticket.journeyorder.application.port.in.JourneyOrderResult.AncillaryItemDto> ancillaryItems
) {}
