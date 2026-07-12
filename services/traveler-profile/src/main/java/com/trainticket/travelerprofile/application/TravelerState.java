package com.trainticket.travelerprofile.application;

import com.trainticket.travelerprofile.domain.TravelerProfile;
import java.time.Instant;

public record TravelerState(
    TravelerProfile aggregate,
    String travelerId,
    String snapshotVersion,
    String accountId,
    TravelerType travelerType,
    String givenName,
    String familyName,
    String contactEmail,
    String contactPhone,
    Instant createdAt,
    Instant updatedAt
) {}
