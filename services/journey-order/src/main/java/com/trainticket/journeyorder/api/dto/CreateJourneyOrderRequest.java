package com.trainticket.journeyorder.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CreateJourneyOrderRequest(
    @NotBlank String accountId,
    @NotBlank String offerId,
    @Min(1) int offerVersion,
    @NotEmpty List<@NotBlank String> travelerRefs,
    @NotEmpty List<@NotBlank String> segmentRefs
) {}
