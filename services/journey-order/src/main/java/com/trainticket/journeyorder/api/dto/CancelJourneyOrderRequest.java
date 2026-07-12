package com.trainticket.journeyorder.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelJourneyOrderRequest(
    @NotBlank String reason
) {}
