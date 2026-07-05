package com.trainticket.travelerprofile.application;

import java.time.Instant;

public record EligibilityResult(
    String travelerId,
    String eligibilityRef,
    boolean eligible,
    Instant validFrom,
    Instant validUntil
) {
}
