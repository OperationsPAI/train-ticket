package com.trainticket.travelerprofile.domain;

import java.time.Instant;
import java.util.Objects;

public record ExternalVerificationFact(
    String credentialRecordId,
    String verificationCaseId,
    ExternalVerificationStatus status,
    String documentType,
    String maskedDocumentNo,
    String documentHash,
    String policyVersion,
    String reasonCode,
    Instant validFrom,
    Instant validUntil,
    Instant recordedAt,
    String sourceEventId
) {
    public ExternalVerificationFact {
        credentialRecordId = requireText(credentialRecordId, "credentialRecordId");
        status = Objects.requireNonNull(status, "status is required");
        if (status != ExternalVerificationStatus.REGISTERED) {
            verificationCaseId = requireText(verificationCaseId, "verificationCaseId");
        }
        documentType = blankToNull(documentType);
        maskedDocumentNo = blankToNull(maskedDocumentNo);
        documentHash = blankToNull(documentHash);
        policyVersion = blankToNull(policyVersion);
        reasonCode = blankToNull(reasonCode);
        validFrom = requireWhen(status == ExternalVerificationStatus.PASSED, validFrom, "validFrom");
        validUntil = requireWhen(status == ExternalVerificationStatus.PASSED, validUntil, "validUntil");
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt is required");
        sourceEventId = requireText(sourceEventId, "sourceEventId");
    }

    public boolean hasDocumentMaterial() {
        return documentType != null || maskedDocumentNo != null || documentHash != null;
    }

    public ExternalVerificationFact withDocumentMaterialFrom(ExternalVerificationFact source) {
        return new ExternalVerificationFact(
            credentialRecordId,
            verificationCaseId,
            status,
            documentType == null ? source.documentType() : documentType,
            maskedDocumentNo == null ? source.maskedDocumentNo() : maskedDocumentNo,
            documentHash == null ? source.documentHash() : documentHash,
            policyVersion,
            reasonCode,
            validFrom,
            validUntil,
            recordedAt,
            sourceEventId
        );
    }

    private static Instant requireWhen(boolean required, Instant value, String name) {
        if (required && value == null) {
            throw new DomainRuleViolation(name + " is required");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
