package com.trainticket.travelerprofile.application;

public record CreateTravelerCommand(
    String accountId,
    TravelerType travelerType,
    String givenName,
    String familyName,
    String dateOfBirth,
    ApiDocumentType documentType,
    String documentNumber,
    String documentIssuingCountry,
    String contactEmail,
    String contactPhone,
    String idempotencyKey,
    String correlationId
) {
}
