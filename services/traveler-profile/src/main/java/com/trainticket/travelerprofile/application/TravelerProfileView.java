package com.trainticket.travelerprofile.application;

import java.time.Instant;
import java.util.List;

public record TravelerProfileView(
    String travelerId,
    String snapshotVersion,
    String accountId,
    TravelerType travelerType,
    String givenName,
    String familyName,
    ApiDocumentType documentType,
    String maskedDocumentRef,
    String contactEmail,
    String contactPhone,
    Instant createdAt,
    Instant updatedAt,
    List<VerificationFactView> verificationFacts
) {
}
