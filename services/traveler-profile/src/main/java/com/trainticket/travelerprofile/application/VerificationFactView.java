package com.trainticket.travelerprofile.application;

import java.time.Instant;

public record VerificationFactView(
    String credentialRecordId,
    String verificationCaseId,
    String status,
    String documentType,
    String maskedDocumentNo,
    String policyVersion,
    String reasonCode,
    Instant validFrom,
    Instant validUntil,
    Instant recordedAt
) {}
