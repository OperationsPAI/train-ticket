package com.trainticket.travelerprofile.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TravelerProfileTest {
    private static final Instant NOW = Instant.parse("2026-07-04T12:00:00Z");
    private static final Instant FUTURE = Instant.parse("2027-07-04T12:00:00Z");
    private static final Instant PAST = Instant.parse("2025-07-04T12:00:00Z");
    private static final Instant LONG_PAST = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant EXPIRED_DATE = Instant.parse("2025-06-01T00:00:00Z");

    // ==============================
    // TravelerProfile Creation
    // ==============================

    @Test
    void createsTravelerProfileInDraftWithTravelerRefAndCreatedEvent() {
        TravelerProfile profile = TravelerProfile.create(
            "account-1", "Zhang Wei", "1990-01-15",
            "client-req-1", NOW, "cmd-create", "corr-1"
        );
        assertEquals(TravelerProfileStatus.DRAFT, profile.status());
        assertTrue(profile.travelerRef().startsWith("TRV-"));
        assertEquals("account-1", profile.accountId());
        assertEquals("Zhang Wei", profile.displayName());
        assertEquals("1990-01-15", profile.birthDate());
        assertTrue(profile.documents().isEmpty());
        assertEquals(1, profile.domainEvents().size());
        TravelerProfileEvent event = profile.domainEvents().getFirst();
        assertInstanceOf(TravelerProfileCreated.class, event);
        assertEquals(1, event.schemaVersion());
        assertEquals(profile.profileId(), event.profileId());
    }

    @Test
    void refusesBlankFieldsOnProfileCreation() {
        assertThrows(DomainRuleViolation.class, () ->
            TravelerProfile.create("", "Zhang Wei", "1990-01-15", "req-1", NOW, "cmd", "corr"));
        assertThrows(DomainRuleViolation.class, () ->
            TravelerProfile.create("account-1", "", "1990-01-15", "req-1", NOW, "cmd", "corr"));
        assertThrows(DomainRuleViolation.class, () ->
            TravelerProfile.create("account-1", "Zhang Wei", "", "req-1", NOW, "cmd", "corr"));
    }

    // ==============================
    // Document Management
    // ==============================

    @Test
    void addsIdentityDocumentAndEmitsDocumentAddedEvent() {
        TravelerProfile profile = sampleDraftProfile();
        Document doc = profile.addDocument(
            DocumentType.IDENTITY_CARD, "110101199001151234", "CN",
            PAST, FUTURE, "Zhang Wei", true,
            NOW, "cmd-add-doc", "cmd-create", "corr-1"
        );
        assertNotNull(doc.documentId());
        assertEquals(DocumentType.IDENTITY_CARD, doc.documentType());
        assertEquals(DocumentStatus.PENDING_VERIFICATION, doc.status());
        assertEquals(1, profile.documents().size());
        assertEquals(doc.documentId(), profile.primaryDocument().documentId());
        TravelerProfileEvent event = profile.domainEvents().getLast();
        assertInstanceOf(DocumentAdded.class, event);
        assertEquals(doc.documentId(), ((DocumentAdded) event).documentId());
    }

    @Test
    void refusesDuplicateDocumentTypeAndNumber() {
        TravelerProfile profile = sampleDraftProfile();
        profile.addDocument(
            DocumentType.IDENTITY_CARD, "110101199001151234", "CN",
            PAST, FUTURE, "Zhang Wei", true,
            NOW, "cmd-add-doc", "cmd-create", "corr-1"
        );
        assertThrows(DomainRuleViolation.class, () ->
            profile.addDocument(
                DocumentType.IDENTITY_CARD, "110101199001151234", "CN",
                PAST, FUTURE, "Zhang Wei", false,
                NOW, "cmd-add-doc-2", "cmd-create", "corr-1"
            ));
    }

    @Test
    void verifiesDocumentAndEmitsDocumentVerifiedEvent() {
        TravelerProfile profile = sampleDraftProfile();
        Document doc = addSampleDocument(profile, true);
        profile.verifyDocument(doc.documentId(), "real-name-service", NOW, "cmd-verify", "cmd-add-doc", "corr-1");
        assertEquals(DocumentStatus.VERIFIED, doc.status());
        assertEquals("real-name-service", doc.verifier());
        TravelerProfileEvent event = profile.domainEvents().getLast();
        assertInstanceOf(DocumentVerified.class, event);
        assertEquals(doc.documentId(), ((DocumentVerified) event).documentId());
    }

    @Test
    void refusesVerificationOfDocumentPastExpiry() {
        TravelerProfile profile = sampleDraftProfile();
        Document doc = profile.addDocument(
            DocumentType.PASSPORT, "E12345678", "CN",
            LONG_PAST, EXPIRED_DATE, "Zhang Wei", true,
            NOW, "cmd-add-doc", "cmd-create", "corr-1"
        );
        assertThrows(DomainRuleViolation.class, () ->
            profile.verifyDocument(doc.documentId(), "real-name-service", NOW, "cmd-verify", "cmd-add-doc", "corr-1"));
    }

    @Test
    void expiresDocumentAndEmitsDocumentExpiredEvent() {
        TravelerProfile profile = sampleDraftProfile();
        Document doc = addSampleDocument(profile, true);
        profile.expireDocument(doc.documentId(), NOW, "cmd-expire", "cmd-add-doc", "corr-1");
        assertEquals(DocumentStatus.EXPIRED, doc.status());
        TravelerProfileEvent event = profile.domainEvents().getLast();
        assertInstanceOf(DocumentExpired.class, event);
        assertEquals(doc.documentId(), ((DocumentExpired) event).documentId());
    }

    @Test
    void revokesDocumentAndEmitsDocumentRevokedEvent() {
        TravelerProfile profile = sampleDraftProfile();
        Document doc = addSampleDocument(profile, true);
        profile.revokeDocument(doc.documentId(), "suspected forgery", NOW, "cmd-revoke", "cmd-add-doc", "corr-1");
        assertEquals(DocumentStatus.REVOKED, doc.status());
        TravelerProfileEvent event = profile.domainEvents().getLast();
        assertInstanceOf(DocumentRevoked.class, event);
        assertEquals(doc.documentId(), ((DocumentRevoked) event).documentId());
    }

    @Test
    void changesPrimaryDocumentAndEmitsPrimaryDocumentChangedEvent() {
        TravelerProfile profile = sampleDraftProfile();
        Document doc1 = addSampleDocument(profile, true);
        Document doc2 = profile.addDocument(
            DocumentType.PASSPORT, "E98765432", "CN",
            PAST, FUTURE, "Zhang Wei", false,
            NOW, "cmd-add-doc-2", "cmd-create", "corr-1"
        );
        profile.setPrimaryDocument(doc2.documentId(), NOW, "cmd-set-primary", "cmd-add-doc-2", "corr-1");
        assertEquals(doc2.documentId(), profile.primaryDocument().documentId());
        TravelerProfileEvent event = profile.domainEvents().getLast();
        assertInstanceOf(PrimaryDocumentChanged.class, event);
        assertEquals(doc1.documentId(), ((PrimaryDocumentChanged) event).previousPrimaryDocumentId());
        assertEquals(doc2.documentId(), ((PrimaryDocumentChanged) event).newPrimaryDocumentId());
    }

    @Test
    void refusesExpiredDocumentAsPrimary() {
        TravelerProfile profile = sampleDraftProfile();
        Document doc1 = addSampleDocument(profile, true);
        // Add a second document that will expire
        Document doc2 = profile.addDocument(
            DocumentType.PASSPORT, "E98765432", "CN",
            LONG_PAST, EXPIRED_DATE, "Zhang Wei", false,
            NOW, "cmd-add-doc-2", "cmd-create", "corr-1"
        );
        // Document has expired date (EXPIRED_DATE is before NOW)
        assertThrows(DomainRuleViolation.class, () ->
            profile.setPrimaryDocument(doc2.documentId(), NOW, "cmd-set-primary", "cmd-add-doc-2", "corr-1"));
    }

    @Test
    void documentMaskedRefMasksNumberAppropriately() {
        Document doc = new Document(
            "doc-1", DocumentType.IDENTITY_CARD, "110101199001151234", "CN",
            PAST, FUTURE, "Zhang Wei", true
        );
        assertEquals("11***1234", doc.maskedDocumentRef());
        Document shortDoc = new Document(
            "doc-2", DocumentType.OTHER, "AB12", "CN",
            PAST, FUTURE, "Test", true
        );
        assertEquals("***AB12", shortDoc.maskedDocumentRef());
    }


    @Test
    void travelerSnapshotSerializationDoesNotContainPlaintextDocumentNumber() {
        TravelerProfile profile = sampleDraftProfile();
        addSampleDocument(profile, true);
        com.trainticket.travelerprofile.application.TravelerState state = new com.trainticket.travelerprofile.application.TravelerState(
            profile,
            "tvl-1",
            "sv-1",
            "account-1",
            com.trainticket.travelerprofile.application.TravelerType.ADULT,
            "Zhang",
            "Wei",
            null,
            null,
            NOW,
            NOW
        );
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

        String json = com.trainticket.travelerprofile.infrastructure.persistence.TravelerJson.snapshot(state, objectMapper).data().toString();

        assertFalse(json.contains("110101199001151234"));
        assertFalse(json.contains("\"documentNumber\""));
        assertTrue(json.contains("maskedDocumentRef"));
        assertTrue(json.contains("documentNumberHash"));
    }

    // ==============================
    // Profile Lifecycle
    // ==============================

    @Test
    void activatesProfileAfterDocumentAddedAndVerified() {
        TravelerProfile profile = sampleDraftProfile();
        Document doc = addSampleDocument(profile, true);
        profile.verifyDocument(doc.documentId(), "real-name-service", NOW, "cmd-verify", "cmd-add-doc", "corr-1");
        profile.activate(NOW, "cmd-activate", "cmd-verify", "corr-1");
        assertEquals(TravelerProfileStatus.ACTIVE, profile.status());
        TravelerProfileEvent event = profile.domainEvents().getLast();
        assertInstanceOf(TravelerProfileActivated.class, event);
    }

    @Test
    void refusesActivationWithoutDocuments() {
        TravelerProfile profile = TravelerProfile.create(
            "account-1", "Zhang Wei", "1990-01-15",
            "req-1", NOW, "cmd-create", "corr-1"
        );
        assertThrows(DomainRuleViolation.class, () ->
            profile.activate(NOW, "cmd-activate", "cmd-create", "corr-1"));
    }

    @Test
    void refusesActivationWithoutPrimaryDocument() {
        TravelerProfile profile = sampleDraftProfile();
        profile.addDocument(
            DocumentType.IDENTITY_CARD, "110101199001151234", "CN",
            PAST, FUTURE, "Zhang Wei", false,
            NOW, "cmd-add-doc", "cmd-create", "corr-1"
        );
        assertThrows(DomainRuleViolation.class, () ->
            profile.activate(NOW, "cmd-activate", "cmd-add-doc", "corr-1"));
    }

    @Test
    void suspendsAndReactivatesProfile() {
        TravelerProfile profile = sampleActiveProfile();
        profile.suspend("compliance hold", NOW.plusSeconds(10), "cmd-suspend", "cmd-activate", "corr-1");
        assertEquals(TravelerProfileStatus.SUSPENDED, profile.status());
        profile.reactivate(NOW.plusSeconds(20), "cmd-reactivate", "cmd-suspend", "corr-1");
        assertEquals(TravelerProfileStatus.ACTIVE, profile.status());
    }

    @Test
    void deactivatesProfileAndEmitsDeactivatedEvent() {
        TravelerProfile profile = sampleActiveProfile();
        profile.deactivate("account closed", NOW.plusSeconds(10), "cmd-deactivate", "cmd-activate", "corr-1");
        assertEquals(TravelerProfileStatus.DEACTIVATED, profile.status());
        TravelerProfileEvent event = profile.domainEvents().getLast();
        assertInstanceOf(TravelerProfileDeactivated.class, event);
    }

    @Test
    void refusesModificationsOnDeactivatedProfile() {
        TravelerProfile profile = sampleActiveProfile();
        profile.deactivate("account closed", NOW, "cmd-deactivate", "cmd-activate", "corr-1");
        assertThrows(DomainRuleViolation.class, () ->
            profile.addDocument(DocumentType.PASSPORT, "E12345678", "CN", PAST, FUTURE, "Zhang Wei", true, NOW, "cmd", "cmd", "corr"));
    }

    // ==============================
    // New Booking Validation
    // ==============================

    @Test
    void profileCanBeReferencedForNewBookingWhenActiveWithVerifiedPrimaryDocument() {
        TravelerProfile profile = sampleActiveProfile();
        profile.requireCanReferenceForNewBooking(NOW);
    }

    @Test
    void refusesBookingReferenceForDraftProfile() {
        TravelerProfile profile = sampleDraftProfile();
        assertThrows(DomainRuleViolation.class, () ->
            profile.requireCanReferenceForNewBooking(NOW));
    }

    @Test
    void refusesBookingReferenceForDeactivatedProfile() {
        TravelerProfile profile = sampleActiveProfile();
        profile.deactivate("closed", NOW, "cmd", "cmd", "corr");
        assertThrows(DomainRuleViolation.class, () ->
            profile.requireCanReferenceForNewBooking(NOW));
    }

    // ==============================
    // Eligibility Management
    // ==============================

    @Test
    void grantsEligibilityAndEmitsEligibilityGrantedEvent() {
        TravelerProfile profile = sampleActiveProfile();
        EligibilitySummary eligibility = profile.grantEligibility(
            "STUDENT", "university-verification", "hash-123",
            NOW, FUTURE, NOW, "cmd-grant", "cmd-activate", "corr-1"
        );
        assertNotNull(eligibility.eligibilityId());
        assertEquals("STUDENT", eligibility.eligibilityType());
        assertTrue(eligibility.isEffectiveAt(NOW));
        assertFalse(eligibility.revoked());
        TravelerProfileEvent event = profile.domainEvents().getLast();
        assertInstanceOf(EligibilityGranted.class, event);
        assertEquals(eligibility.eligibilityId(), ((EligibilityGranted) event).eligibilityId());
    }

    @Test
    void revokesEligibilityAndEmitsEligibilityRevokedEvent() {
        TravelerProfile profile = sampleActiveProfile();
        EligibilitySummary eligibility = sampleEligibility(profile);
        profile.revokeEligibility(eligibility.eligibilityId(), "student status no longer valid", NOW, "cmd-revoke", "cmd-grant", "corr-1");
        assertTrue(eligibility.revoked());
        assertFalse(eligibility.isEffectiveAt(NOW));
        TravelerProfileEvent event = profile.domainEvents().getLast();
        assertInstanceOf(EligibilityRevoked.class, event);
    }

    @Test
    void expiresEligibilityAndEmitsEligibilityExpiredEvent() {
        TravelerProfile profile = sampleActiveProfile();
        EligibilitySummary eligibility = sampleEligibility(profile);
        profile.expireEligibility(eligibility.eligibilityId(), NOW, "cmd-expire", "cmd-grant", "corr-1");
        assertTrue(eligibility.revoked());
        assertFalse(eligibility.isEffectiveAt(NOW));
    }

    @Test
    void refusesNewQuoteWithRevokedEligibility() {
        TravelerProfile profile = sampleActiveProfile();
        EligibilitySummary eligibility = sampleEligibility(profile);
        profile.revokeEligibility(eligibility.eligibilityId(), "no longer valid", NOW, "cmd-revoke", "cmd-grant", "corr-1");
        assertThrows(DomainRuleViolation.class, () ->
            eligibility.requireEffectiveForNewQuote(NOW));
    }

    @Test
    void effectiveEligibilitiesOnlyReturnsValidNonRevokedEntries() {
        TravelerProfile profile = sampleActiveProfile();
        EligibilitySummary e1 = sampleEligibility(profile);
        EligibilitySummary e2 = profile.grantEligibility(
            "SENIOR", "age-verification", "hash-456",
            NOW, FUTURE, NOW, "cmd-grant-2", "cmd-activate", "corr-1"
        );
        profile.revokeEligibility(e1.eligibilityId(), "no longer valid", NOW, "cmd-revoke", "cmd-grant", "corr-1");
        List<EligibilitySummary> effective = profile.effectiveEligibilities(NOW);
        assertEquals(1, effective.size());
        assertEquals(e2.eligibilityId(), effective.getFirst().eligibilityId());
    }

    @Test
    void eligibilitySummaryValidatesDateRange() {
        assertThrows(DomainRuleViolation.class, () ->
            new EligibilitySummary("e-1", "STUDENT", "source", "hash", FUTURE, NOW));
    }

    // ==============================
    // Preference Management
    // ==============================

    @Test
    void updatesPreferenceAndEmitsPreferencesUpdatedEvent() {
        TravelerProfile profile = sampleActiveProfile();
        profile.updatePreference("seat-preference", "aisle", NOW, "cmd-update-pref", "cmd-activate", "corr-1");
        assertEquals("aisle", profile.preferences().preferences().get("seat-preference"));
        assertEquals(2, profile.preferences().version());
        TravelerProfileEvent event = profile.domainEvents().getLast();
        assertInstanceOf(PreferencesUpdated.class, event);
        assertEquals("seat-preference", ((PreferencesUpdated) event).preferenceType());
    }

    @Test
    void removesPreferenceAndUpdatesVersion() {
        TravelerProfile profile = sampleActiveProfile();
        profile.updatePreference("seat-preference", "aisle", NOW, "cmd-update", "cmd-activate", "corr-1");
        profile.removePreference("seat-preference", NOW, "cmd-remove", "cmd-update", "corr-1");
        assertFalse(profile.preferences().preferences().containsKey("seat-preference"));
        assertEquals(3, profile.preferences().version());
    }

    @Test
    void preferenceSnapshotImmutableAfterUpdate() {
        TravelerProfile profile = sampleActiveProfile();
        profile.updatePreference("seat-preference", "aisle", NOW, "cmd-update", "cmd-activate", "corr-1");
        Map<String, String> originalPrefs = profile.preferences().preferences();
        profile.updatePreference("meal-preference", "vegetarian", NOW, "cmd-update-2", "cmd-update", "corr-1");
        assertEquals(1, originalPrefs.size());
        assertEquals(2, profile.preferences().preferences().size());
    }

    // ==============================
    // General Invariants
    // ==============================

    @Test
    void eventMetadataGeneratesIdAndEnforcesInvariants() {
        EventMetadata metadata = EventMetadata.create(NOW, "cmd-1", "cause-1", "corr-1", Map.of("key", "value"));
        assertNotNull(metadata.eventId());
        assertEquals(NOW, metadata.occurredAt());
        assertEquals(1, metadata.schemaVersion());
        assertFalse(metadata.attributes().isEmpty());
        assertThrows(DomainRuleViolation.class, () ->
            EventMetadata.create(NOW, "", "cause-1", "corr-1", Map.of()));
    }

    @Test
    void documentTypeEnumContainsExpectedValues() {
        assertEquals(9, DocumentType.values().length);
    }

    @Test
    void profileHasTimelineOfEventsAfterMultipleCommands() {
        TravelerProfile profile = sampleActiveProfile();
        profile.updatePreference("seat-preference", "window", NOW, "cmd-pref", "cmd-activate", "corr-1");
        profile.grantEligibility("STUDENT", "uni-verify", "hash-1", NOW, FUTURE, NOW, "cmd-elig", "cmd-pref", "corr-1");
        List<TravelerProfileEvent> events = profile.domainEvents();
        assertEquals(List.of(
            "TravelerProfileCreated",
            "DocumentAdded",
            "DocumentVerified",
            "TravelerProfileActivated",
            "PreferencesUpdated",
            "EligibilityGranted"
        ), events.stream().map(TravelerProfileEvent::eventType).toList());
    }

    @Test
    void eventSealedInterfaceCoversAllEventTypes() {
        assertInstanceOf(TravelerProfileEvent.class, new TravelerProfileCreated("id", "ref", "aid", "name", "bd", null));
        assertInstanceOf(TravelerProfileEvent.class, new TravelerProfileActivated("id", null));
        assertInstanceOf(TravelerProfileEvent.class, new TravelerProfileSuspended("id", "reason", null));
        assertInstanceOf(TravelerProfileEvent.class, new TravelerProfileDeactivated("id", "reason", null));
        assertInstanceOf(TravelerProfileEvent.class, new DocumentAdded("id", "did", DocumentType.IDENTITY_CARD, "CN", true, null));
        assertInstanceOf(TravelerProfileEvent.class, new DocumentVerified("id", "did", DocumentType.IDENTITY_CARD, "verifier", null));
        assertInstanceOf(TravelerProfileEvent.class, new DocumentExpired("id", "did", DocumentType.IDENTITY_CARD, null));
        assertInstanceOf(TravelerProfileEvent.class, new DocumentRevoked("id", "did", DocumentType.IDENTITY_CARD, "reason", null));
        assertInstanceOf(TravelerProfileEvent.class, new PrimaryDocumentChanged("id", "old", "new", null));
        assertInstanceOf(TravelerProfileEvent.class, new EligibilityGranted("id", "eid", "STUDENT", "src", NOW, FUTURE, null));
        assertInstanceOf(TravelerProfileEvent.class, new EligibilityRevoked("id", "eid", "STUDENT", "reason", null));
        assertInstanceOf(TravelerProfileEvent.class, new EligibilityExpired("id", "eid", "STUDENT", null));
        assertInstanceOf(TravelerProfileEvent.class, new PreferencesUpdated("id", "type", "val", null));
    }

    // ==============================
    // Document invariants
    // ==============================

    @Test
    void documentRefusesExpiresAtBeforeIssuedAt() {
        assertThrows(DomainRuleViolation.class, () ->
            new Document("doc-1", DocumentType.IDENTITY_CARD, "110101199001151234", "CN",
                FUTURE, PAST, "Zhang Wei", true));
    }

    @Test
    void verifiedDocumentIsActiveAtValidMoment() {
        Document doc = new Document("doc-1", DocumentType.IDENTITY_CARD, "110101199001151234", "CN",
            PAST, FUTURE, "Zhang Wei", true);
        doc.verify("real-name-service", PAST);
        assertTrue(doc.isActiveAt(NOW));
    }

    @Test
    void expiredDocumentIsNotActive() {
        Document doc = new Document("doc-1", DocumentType.IDENTITY_CARD, "110101199001151234", "CN",
            LONG_PAST, EXPIRED_DATE, "Zhang Wei", true);
        doc.expire();
        assertFalse(doc.isActiveAt(NOW));
    }

    // ==============================
    // PreferenceSnapshot invariants
    // ==============================

    @Test
    void preferenceSnapshotRefusesNegativeVersion() {
        assertThrows(DomainRuleViolation.class, () ->
            new PreferenceSnapshot(Map.of(), Map.of(), 0));
    }

    @Test
    void preferenceSnapshotWithUpdatedPreferenceReturnsNewInstance() {
        PreferenceSnapshot snapshot = new PreferenceSnapshot(Map.of("seat", "window"), Map.of(), 1);
        PreferenceSnapshot updated = snapshot.withUpdatedPreference("meal", "vegetarian");
        assertEquals(1, snapshot.preferences().size());
        assertEquals(2, updated.preferences().size());
        assertEquals(2, updated.version());
    }

    // ==============================
    // Helpers
    // ==============================

    private TravelerProfile sampleDraftProfile() {
        return TravelerProfile.create(
            "account-1", "Zhang Wei", "1990-01-15",
            "req-1", NOW, "cmd-create", "corr-1"
        );
    }

    private TravelerProfile sampleActiveProfile() {
        TravelerProfile profile = sampleDraftProfile();
        Document doc = addSampleDocument(profile, true);
        profile.verifyDocument(doc.documentId(), "real-name-service", NOW, "cmd-verify", "cmd-add-doc", "corr-1");
        profile.activate(NOW, "cmd-activate", "cmd-verify", "corr-1");
        return profile;
    }

    private Document addSampleDocument(TravelerProfile profile, boolean asPrimary) {
        return profile.addDocument(
            DocumentType.IDENTITY_CARD, "110101199001151234", "CN",
            PAST, FUTURE, "Zhang Wei", asPrimary,
            NOW, "cmd-add-doc", "cmd-create", "corr-1"
        );
    }

    private EligibilitySummary sampleEligibility(TravelerProfile profile) {
        return profile.grantEligibility(
            "STUDENT", "university-verification", "hash-123",
            NOW, FUTURE, NOW, "cmd-grant", "cmd-activate", "corr-1"
        );
    }
}
