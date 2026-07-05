package com.trainticket.journeyorder.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreateJourneyOrderRequest(
    @NotBlank String accountId,
    @NotBlank String offerId,
    int offerVersion,
    @NotNull List<@NotBlank String> travelerRefs,
    @NotNull List<@NotBlank String> segmentRefs
) {}
