package com.trainticket.travelerprofile.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class TravelerProfile {
    private final String profileId;
    private final String travelerRef;
    private final String accountId;
    private String displayName;
    private String birthDate;
    private TravelerProfileStatus status;
    private String statusReason;
    private final Map<String, Document> documents;
    private String primaryDocumentId;
    private final Map<String, EligibilitySummary> eligibilitySummaries;
    private PreferenceSnapshot preferences;
    private final List<TravelerProfileEvent> domainEvents;

    private TravelerProfile(
        String profileId,
        String travelerRef,
        String accountId,
        String displayName,
        String birthDate
    ) {
        this.profileId = requireText(profileId, "profileId");
        this.travelerRef = requireText(travelerRef, "travelerRef");
        this.accountId = requireText(accountId, "accountId");
        this.displayName = requireText(displayName, "displayName");
        this.birthDate = requireText(birthDate, "birthDate");
        this.status = TravelerProfileStatus.DRAFT;
        this.documents = new HashMap<>();
        this.eligibilitySummaries = new HashMap<>();
        this.preferences = new PreferenceSnapshot(Collections.emptyMap(), Collections.emptyMap(), 1);
        this.domainEvents = new ArrayList<>();
    }

    public static TravelerProfile create(
        String accountId,
        String displayName,
        String birthDate,
        String clientRequestId,
        Instant now,
        String sourceCommandId,
        String correlationId
    ) {
        String profileId = UUID.randomUUID().toString();
        String travelerRef = "TRV-" + profileId.substring(0, 8).toUpperCase();
        TravelerProfile profile = new TravelerProfile(profileId, travelerRef, accountId, displayName, birthDate);
        profile.domainEvents.add(new TravelerProfileCreated(
            profileId, travelerRef, accountId, displayName, birthDate,
            EventMetadata.create(now, sourceCommandId, sourceCommandId, correlationId,
                Map.of("status", profile.status.name(), "travelerRef", travelerRef))
        ));
        return profile;
    }

    // --- Accessors ---
    public String profileId() { return profileId; }
    public String travelerRef() { return travelerRef; }
    public String accountId() { return accountId; }
    public String displayName() { return displayName; }
    public String birthDate() { return birthDate; }
    public TravelerProfileStatus status() { return status; }
    public String statusReason() { return statusReason; }
    public List<Document> documents() { return List.copyOf(documents.values()); }
    public Document primaryDocument() { return primaryDocumentId != null ? documents.get(primaryDocumentId) : null; }
    public List<EligibilitySummary> eligibilitySummaries() { return List.copyOf(eligibilitySummaries.values()); }
    public PreferenceSnapshot preferences() { return preferences; }
    public List<TravelerProfileEvent> domainEvents() { return List.copyOf(domainEvents); }

    // --- Lifecycle commands ---
    public void activate(Instant now, String sourceCommandId, String causationId, String correlationId) {
        requireState(TravelerProfileStatus.DRAFT);
        if (documents.isEmpty()) {
            throw new DomainRuleViolation("cannot activate TravelerProfile without at least one identity document");
        }
        if (primaryDocumentId == null) {
            throw new DomainRuleViolation("cannot activate TravelerProfile without a primary document");
        }
        Document primary = documents.get(primaryDocumentId);
        primary.requireActiveForNewBooking(now);
        this.status = TravelerProfileStatus.ACTIVE;
        this.statusReason = "profile activated";
        domainEvents.add(new TravelerProfileActivated(profileId,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("previousStatus", TravelerProfileStatus.DRAFT.name(), "newStatus", TravelerProfileStatus.ACTIVE.name()))));
    }

    public void suspend(String reason, Instant now, String sourceCommandId, String causationId, String correlationId) {
        requireState(TravelerProfileStatus.ACTIVE);
        this.status = TravelerProfileStatus.SUSPENDED;
        this.statusReason = requireText(reason, "reason");
        domainEvents.add(new TravelerProfileSuspended(profileId, reason,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("previousStatus", TravelerProfileStatus.ACTIVE.name(), "newStatus", TravelerProfileStatus.SUSPENDED.name()))));
    }

    public void reactivate(Instant now, String sourceCommandId, String causationId, String correlationId) {
        requireState(TravelerProfileStatus.SUSPENDED);
        if (documents.isEmpty()) {
            throw new DomainRuleViolation("cannot reactivate TravelerProfile without at least one identity document");
        }
        this.status = TravelerProfileStatus.ACTIVE;
        this.statusReason = "profile reactivated";
        domainEvents.add(new TravelerProfileActivated(profileId,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("previousStatus", TravelerProfileStatus.SUSPENDED.name(), "newStatus", TravelerProfileStatus.ACTIVE.name()))));
    }

    public void deactivate(String reason, Instant now, String sourceCommandId, String causationId, String correlationId) {
        if (status == TravelerProfileStatus.DEACTIVATED) {
            return;
        }
        if (status == TravelerProfileStatus.DRAFT) {
            this.status = TravelerProfileStatus.DEACTIVATED;
            this.statusReason = requireText(reason, "reason");
            domainEvents.add(new TravelerProfileDeactivated(profileId, reason,
                EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                    Map.of("previousStatus", TravelerProfileStatus.DRAFT.name(), "newStatus", TravelerProfileStatus.DEACTIVATED.name()))));
            return;
        }
        requireStateIn(TravelerProfileStatus.ACTIVE, TravelerProfileStatus.SUSPENDED);
        this.status = TravelerProfileStatus.DEACTIVATED;
        this.statusReason = requireText(reason, "reason");
        domainEvents.add(new TravelerProfileDeactivated(profileId, reason,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("previousStatus", status.name(), "newStatus", TravelerProfileStatus.DEACTIVATED.name()))));
    }

    // --- Document commands ---
    public Document addDocument(
        DocumentType documentType, String documentNumber, String issuingCountry,
        Instant issuedAt, Instant expiresAt, String displayName, boolean setAsPrimary,
        Instant now, String sourceCommandId, String causationId, String correlationId
    ) {
        requireNotDeactivated();
        String documentId = UUID.randomUUID().toString();
        String docHash = Integer.toHexString(Objects.hash(documentNumber));
        for (Document existing : documents.values()) {
            if (existing.documentType() == documentType && existing.documentNumberHash().equals(docHash)) {
                throw new DomainRuleViolation("duplicate document type " + documentType + " for this profile");
            }
        }
        Document doc = new Document(documentId, documentType, documentNumber, issuingCountry, issuedAt, expiresAt, displayName, setAsPrimary);
        documents.put(documentId, doc);
        if (setAsPrimary || documents.size() == 1) {
            this.primaryDocumentId = documentId;
        }
        domainEvents.add(new DocumentAdded(profileId, documentId, documentType, issuingCountry, setAsPrimary || documents.size() == 1,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("documentType", documentType.name(), "isPrimary", String.valueOf(setAsPrimary || documents.size() == 1)))));
        return doc;
    }

    public void setPrimaryDocument(String documentId, Instant now, String sourceCommandId, String causationId, String correlationId) {
        requireNotDeactivated();
        Document doc = documents.get(documentId);
        if (doc == null) {
            throw new DomainRuleViolation("unknown document " + documentId);
        }
        if (doc.status() == DocumentStatus.EXPIRED || doc.status() == DocumentStatus.REVOKED) {
            throw new DomainRuleViolation("cannot set expired or revoked document as primary");
        }
        String previousPrimaryId = this.primaryDocumentId;
        if (now.isAfter(doc.expiresAt())) {
            throw new DomainRuleViolation("cannot set expired document as primary: document expired at " + doc.expiresAt());
        }
        this.primaryDocumentId = documentId;
        domainEvents.add(new PrimaryDocumentChanged(profileId, previousPrimaryId, documentId,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("previousPrimary", previousPrimaryId != null ? previousPrimaryId : "none"))));
    }

    public void verifyDocument(String documentId, String verifierName, Instant now, String sourceCommandId, String causationId, String correlationId) {
        requireNotDeactivated();
        Document doc = documents.get(documentId);
        if (doc == null) {
            throw new DomainRuleViolation("unknown document " + documentId);
        }
        doc.verify(verifierName, now);
        domainEvents.add(new DocumentVerified(profileId, documentId, doc.documentType(), verifierName,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("documentType", doc.documentType().name(), "verifier", verifierName))));
    }

    public void expireDocument(String documentId, Instant now, String sourceCommandId, String causationId, String correlationId) {
        requireNotDeactivated();
        Document doc = documents.get(documentId);
        if (doc == null) {
            throw new DomainRuleViolation("unknown document " + documentId);
        }
        doc.expire();
        domainEvents.add(new DocumentExpired(profileId, documentId, doc.documentType(),
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("documentType", doc.documentType().name()))));
    }

    public void revokeDocument(String documentId, String reason, Instant now, String sourceCommandId, String causationId, String correlationId) {
        requireNotDeactivated();
        Document doc = documents.get(documentId);
        if (doc == null) {
            throw new DomainRuleViolation("unknown document " + documentId);
        }
        doc.revoke(reason);
        domainEvents.add(new DocumentRevoked(profileId, documentId, doc.documentType(), reason,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("documentType", doc.documentType().name(), "reason", reason))));
    }

    // --- Eligibility commands ---
    public EligibilitySummary grantEligibility(
        String eligibilityType, String eligibilitySource, String evidenceHash,
        Instant validFrom, Instant validUntil, Instant now,
        String sourceCommandId, String causationId, String correlationId
    ) {
        requireNotDeactivated();
        String eligibilityId = UUID.randomUUID().toString();
        EligibilitySummary summary = new EligibilitySummary(eligibilityId, eligibilityType, eligibilitySource, evidenceHash, validFrom, validUntil);
        eligibilitySummaries.put(eligibilityId, summary);
        domainEvents.add(new EligibilityGranted(profileId, eligibilityId, eligibilityType, eligibilitySource, validFrom, validUntil,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("eligibilityType", eligibilityType, "eligibilitySource", eligibilitySource))));
        return summary;
    }

    public void revokeEligibility(String eligibilityId, String reason, Instant now, String sourceCommandId, String causationId, String correlationId) {
        requireNotDeactivated();
        EligibilitySummary summary = eligibilitySummaries.get(eligibilityId);
        if (summary == null) {
            throw new DomainRuleViolation("unknown eligibility " + eligibilityId);
        }
        summary.revoke(reason);
        domainEvents.add(new EligibilityRevoked(profileId, eligibilityId, summary.eligibilityType(), reason,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("eligibilityType", summary.eligibilityType(), "reason", reason))));
    }

    public void expireEligibility(String eligibilityId, Instant now, String sourceCommandId, String causationId, String correlationId) {
        requireNotDeactivated();
        EligibilitySummary summary = eligibilitySummaries.get(eligibilityId);
        if (summary == null) {
            throw new DomainRuleViolation("unknown eligibility " + eligibilityId);
        }
        summary.revoke("expired at " + summary.validUntil());
        domainEvents.add(new EligibilityExpired(profileId, eligibilityId, summary.eligibilityType(),
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("eligibilityType", summary.eligibilityType()))));
    }

    // --- Preference commands ---
    public void updatePreference(String key, String value, Instant now, String sourceCommandId, String causationId, String correlationId) {
        requireNotDeactivated();
        this.preferences = this.preferences.withUpdatedPreference(key, value);
        domainEvents.add(new PreferencesUpdated(profileId, key, value,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("preferenceType", key, "preferenceVersion", String.valueOf(preferences.version())))));
    }

    public void removePreference(String key, Instant now, String sourceCommandId, String causationId, String correlationId) {
        requireNotDeactivated();
        this.preferences = this.preferences.withRemovedPreference(key);
        domainEvents.add(new PreferencesUpdated(profileId, key, "",
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("preferenceType", key, "preferenceVersion", String.valueOf(preferences.version()), "removed", "true"))));
    }

    // --- Validation for downstream consumers ---
    public void requireCanReferenceForNewBooking(Instant moment) {
        if (status == TravelerProfileStatus.DRAFT) {
            throw new DomainRuleViolation("profile " + profileId + " is in DRAFT status and cannot be referenced for new bookings");
        }
        if (status == TravelerProfileStatus.DEACTIVATED) {
            throw new DomainRuleViolation("profile " + profileId + " is DEACTIVATED and cannot be referenced for new bookings");
        }
        if (primaryDocumentId == null) {
            throw new DomainRuleViolation("profile " + profileId + " has no primary document and cannot be referenced for new bookings");
        }
        Document primary = documents.get(primaryDocumentId);
        if (primary == null) {
            throw new DomainRuleViolation("profile " + profileId + " primary document not found");
        }
        primary.requireActiveForNewBooking(moment);
    }

    public List<EligibilitySummary> effectiveEligibilities(Instant moment) {
        return eligibilitySummaries.values().stream()
            .filter(e -> e.isEffectiveAt(moment))
            .toList();
    }

    // --- Internal helpers ---
    private void requireState(TravelerProfileStatus expected) {
        if (status != expected) {
            throw new DomainRuleViolation("expected profile state " + expected + " but was " + status);
        }
    }

    private void requireStateIn(TravelerProfileStatus... allowed) {
        for (TravelerProfileStatus s : allowed) {
            if (status == s) return;
        }
        throw new DomainRuleViolation("profile state " + status + " is not allowed for this operation");
    }

    private void requireNotDeactivated() {
        if (status == TravelerProfileStatus.DEACTIVATED) {
            throw new DomainRuleViolation("profile " + profileId + " is DEACTIVATED and cannot be modified");
        }
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
