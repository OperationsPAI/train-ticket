package com.trainticket.travelerprofile.domain;

import java.time.Instant;
import java.util.Objects;

public final class Document {
    private final String documentId;
    private final DocumentType documentType;
    private final String documentNumber;
    private final String documentNumberHash;
    private final String issuingCountry;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final String displayName;
    private final boolean primaryDocument;
    private DocumentStatus status;
    private String statusReason;
    private Instant verifiedAt;
    private String verifier;

    public Document(
        String documentId,
        DocumentType documentType,
        String documentNumber,
        String issuingCountry,
        Instant issuedAt,
        Instant expiresAt,
        String displayName,
        boolean primaryDocument
    ) {
        this.documentId = requireText(documentId, "documentId");
        this.documentType = Objects.requireNonNull(documentType, "documentType is required");
        this.documentNumber = requireText(documentNumber, "documentNumber");
        this.documentNumberHash = Integer.toHexString(Objects.hash(documentNumber));
        this.issuingCountry = requireText(issuingCountry, "issuingCountry");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt is required");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt is required");
        this.displayName = requireText(displayName, "displayName");
        this.primaryDocument = primaryDocument;
        this.status = DocumentStatus.PENDING_VERIFICATION;
        if (!expiresAt.isAfter(issuedAt)) {
            throw new DomainRuleViolation("document expiresAt must be after issuedAt");
        }
    }


    public static Document rehydrate(
        String documentId,
        DocumentType documentType,
        String documentNumber,
        String issuingCountry,
        Instant issuedAt,
        Instant expiresAt,
        String displayName,
        boolean primaryDocument,
        DocumentStatus status,
        String statusReason,
        Instant verifiedAt,
        String verifier
    ) {
        Document document = new Document(documentId, documentType, documentNumber, issuingCountry, issuedAt, expiresAt, displayName, primaryDocument);
        document.status = Objects.requireNonNull(status, "status is required");
        document.statusReason = statusReason;
        document.verifiedAt = verifiedAt;
        document.verifier = verifier;
        return document;
    }

    public String documentId() { return documentId; }
    public DocumentType documentType() { return documentType; }
    public String documentNumber() { return documentNumber; }
    public String documentNumberHash() { return documentNumberHash; }
    public String issuingCountry() { return issuingCountry; }
    public Instant issuedAt() { return issuedAt; }
    public Instant expiresAt() { return expiresAt; }
    public String displayName() { return displayName; }
    public boolean primaryDocument() { return primaryDocument; }
    public DocumentStatus status() { return status; }
    public String statusReason() { return statusReason; }
    public Instant verifiedAt() { return verifiedAt; }
    public String verifier() { return verifier; }

    public void verify(String verifierName, Instant now) {
        if (status == DocumentStatus.VERIFIED) {
            return;
        }
        if (status == DocumentStatus.EXPIRED || now.isAfter(expiresAt)) {
            throw new DomainRuleViolation("cannot verify expired document " + documentId);
        }
        this.status = DocumentStatus.VERIFIED;
        this.verifiedAt = Objects.requireNonNull(now, "now is required");
        this.verifier = requireText(verifierName, "verifierName");
        this.statusReason = "verified by " + verifierName;
    }

    public void expire() {
        if (status == DocumentStatus.EXPIRED) {
            return;
        }
        this.status = DocumentStatus.EXPIRED;
        this.statusReason = "document expired at " + expiresAt;
    }

    public void revoke(String reason) {
        if (status == DocumentStatus.REVOKED) {
            return;
        }
        this.status = DocumentStatus.REVOKED;
        this.statusReason = requireText(reason, "reason");
    }

    public boolean isActiveAt(Instant moment) {
        return status == DocumentStatus.VERIFIED && !moment.isAfter(expiresAt);
    }

    public void requireActiveForNewBooking(Instant moment) {
        if (status == DocumentStatus.EXPIRED || status == DocumentStatus.REVOKED) {
            throw new DomainRuleViolation("document " + documentId + " is " + status + " and cannot be used for new bookings");
        }
        if (moment.isAfter(expiresAt)) {
            throw new DomainRuleViolation("document " + documentId + " has expired at " + expiresAt);
        }
        if (status == DocumentStatus.PENDING_VERIFICATION) {
            throw new DomainRuleViolation("document " + documentId + " is not yet verified and cannot be used for new bookings");
        }
    }

    public String maskedDocumentRef() {
        if (documentNumber.length() <= 4) {
            return "***" + documentNumber;
        }
        return documentNumber.substring(0, 2) + "***" + documentNumber.substring(documentNumber.length() - 4);
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
