package com.trainticket.travelerprofile.application;

public record UpdateTravelerCommand(
    String travelerId,
    String accountId,
    TravelerType travelerType,
    String givenName,
    String familyName,
    ApiDocumentType documentType,
    String documentNumber,
    String contactEmail,
    String contactPhone,
    String idempotencyKey,
    String correlationId
) {
}
