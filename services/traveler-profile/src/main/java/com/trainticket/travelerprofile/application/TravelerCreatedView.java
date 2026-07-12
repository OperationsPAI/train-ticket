package com.trainticket.travelerprofile.application;

import java.time.Instant;

public record TravelerCreatedView(
    String travelerId,
    String snapshotVersion,
    TravelerType travelerType,
    Instant createdAt
) {
}
