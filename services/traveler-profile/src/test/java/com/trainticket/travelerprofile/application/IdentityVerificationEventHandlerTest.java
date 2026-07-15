package com.trainticket.travelerprofile.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trainticket.platformkit.idempotency.InMemoryIdempotencyStore;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.travelerprofile.domain.Document;
import com.trainticket.travelerprofile.domain.DocumentStatus;
import com.trainticket.travelerprofile.domain.DomainRuleViolation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdentityVerificationEventHandlerTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordsVerificationPassedAndDeduplicatesByEnvelopeEventId() {
        InMemoryTravelerProfileStore store = new InMemoryTravelerProfileStore();
        TravelerProfileService service = new TravelerProfileService(
            store,
            new InMemoryIdempotencyStore(),
            new RecordingPublisher(),
            objectMapper,
            Clock.fixed(Instant.parse("2026-07-05T10:30:00Z"), ZoneOffset.UTC)
        );
        String travelerId = service.create(new CreateTravelerCommand(
            "acct-1", TravelerType.ADULT, "Ada", "Lovelace", ApiDocumentType.ID_CARD, "E12345678",
            null, null, "idem-1", "corr-018f0000-0000-7000-8000-000000000001"
        ), "fingerprint-1").travelerId();
        ConsumedEventLog consumedEventLog = new ConsumedEventLog();
        IdentityVerificationEventHandler identityHandler = new IdentityVerificationEventHandler(service, objectMapper);
        DeduplicatingEventHandler handler = new DeduplicatingEventHandler(consumedEventLog, identityHandler);
        EventEnvelope envelope = envelope("evt-018f0000-0000-7000-8000-000000000101", "VerificationPassed", objectMapper.createObjectNode()
            .put("verificationCaseId", "ivc-018f0000-0000-7000-8000-000000000102")
            .put("travelerId", travelerId)
            .put("credentialRecordId", "crd-018f0000-0000-7000-8000-000000000103")
            .put("simOutcome", "MATCH")
            .put("simResultRef", "simres-018f0000-0000-7000-8000-000000000104")
            .put("verificationStatus", "PASSED")
            .put("validFrom", "2026-07-05T10:30:00Z")
            .put("validUntil", "2027-07-05T10:30:00Z")
            .put("policyVersion", "iv-policy-2026")
            .put("completedAt", "2026-07-05T10:31:00Z")
            .put("aggregateVersion", 2)
        );

        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));

        TravelerProfileView view = service.get(travelerId);
        assertTrue(consumedEventLog.hasConsumed(envelope.eventId()));
        assertEquals("sv-2", view.snapshotVersion());
        assertEquals(1, view.verificationFacts().size());
        VerificationFactView fact = view.verificationFacts().getFirst();
        assertEquals("crd-018f0000-0000-7000-8000-000000000103", fact.credentialRecordId());
        assertEquals("PASSED", fact.status());
        assertEquals("iv-policy-2026", fact.policyVersion());
        assertEquals(Instant.parse("2026-07-05T10:31:00Z"), fact.recordedAt());
    }

    @Test
    void recordsCredentialRegistrationAndFailedVerificationRevokesMatchingDocument() {
        InMemoryTravelerProfileStore store = new InMemoryTravelerProfileStore();
        TravelerProfileService service = new TravelerProfileService(
            store,
            new InMemoryIdempotencyStore(),
            new RecordingPublisher(),
            objectMapper,
            Clock.fixed(Instant.parse("2026-07-05T10:30:00Z"), ZoneOffset.UTC)
        );
        String travelerId = service.create(new CreateTravelerCommand(
            "acct-1", TravelerType.ADULT, "Ada", "Lovelace", ApiDocumentType.ID_CARD, "E12345678",
            null, null, "idem-2", "corr-018f0000-0000-7000-8000-000000000201"
        ), "fingerprint-2").travelerId();
        IdentityVerificationEventHandler handler = new IdentityVerificationEventHandler(service, objectMapper);
        Document document = store.findByTravelerId(travelerId).orElseThrow().aggregate().primaryDocument();
        EventEnvelope registered = envelope("evt-018f0000-0000-7000-8000-000000000202", "CredentialRegistered", objectMapper.createObjectNode()
            .put("credentialRecordId", "crd-018f0000-0000-7000-8000-000000000203")
            .put("travelerId", travelerId)
            .put("profileSnapshotVersion", "sv-1")
            .put("documentType", "ID_CARD")
            .put("maskedDocumentNo", document.maskedDocumentRef())
            .put("documentHash", document.documentNumberHash())
            .put("materialFingerprint", "mat-1")
            .put("credentialStatus", "REGISTERED")
            .put("registeredAt", "2026-07-05T10:30:00Z")
            .put("aggregateVersion", 1)
        );
        EventEnvelope failed = envelope("evt-018f0000-0000-7000-8000-000000000204", "VerificationFailed", objectMapper.createObjectNode()
            .put("verificationCaseId", "ivc-018f0000-0000-7000-8000-000000000205")
            .put("travelerId", travelerId)
            .put("credentialRecordId", "crd-018f0000-0000-7000-8000-000000000203")
            .put("simOutcome", "REJECTED")
            .put("simResultRef", "simres-018f0000-0000-7000-8000-000000000206")
            .put("verificationStatus", "FAILED")
            .put("reasonCode", "NAME_DOCUMENT_MISMATCH")
            .put("policyVersion", "iv-policy-2026")
            .put("completedAt", "2026-07-05T10:31:00Z")
            .put("aggregateVersion", 2)
        );

        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(registered));
        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(failed));

        TravelerState state = store.findByTravelerId(travelerId).orElseThrow();
        assertEquals(DocumentStatus.REVOKED, state.aggregate().primaryDocument().status());
        assertThrows(DomainRuleViolation.class, () -> state.aggregate().requireCanReferenceForNewBooking(Instant.parse("2026-07-05T10:31:01Z")));
        assertEquals(2, service.get(travelerId).verificationFacts().size());
    }

    @Test
    void ackSkipsConformantUnknownTravelerWithoutFatalFailure() {
        TravelerProfileService service = new TravelerProfileService(
            new InMemoryTravelerProfileStore(),
            new InMemoryIdempotencyStore(),
            new RecordingPublisher(),
            objectMapper,
            Clock.systemUTC()
        );
        IdentityVerificationEventHandler handler = new IdentityVerificationEventHandler(service, objectMapper);
        EventEnvelope envelope = envelope("evt-018f0000-0000-7000-8000-000000000111", "VerificationFailed", objectMapper.createObjectNode()
            .put("verificationCaseId", "ivc-018f0000-0000-7000-8000-000000000112")
            .put("travelerId", "tvl-018f0000-0000-7000-8000-000000000113")
            .put("credentialRecordId", "crd-018f0000-0000-7000-8000-000000000114")
            .put("simOutcome", "REJECTED")
            .put("simResultRef", "simres-018f0000-0000-7000-8000-000000000115")
            .put("verificationStatus", "FAILED")
            .put("reasonCode", "DOCUMENT_NOT_FOUND")
            .put("policyVersion", "iv-policy-2026")
            .put("completedAt", "2026-07-05T10:31:00Z")
            .put("aggregateVersion", 2)
        );

        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));
    }

    private EventEnvelope envelope(String eventId, String eventType, ObjectNode payload) {
        return new EventEnvelope(
            eventId,
            eventType,
            Instant.parse("2026-07-05T10:30:00Z"),
            "corr-018f0000-0000-7000-8000-000000000011",
            "evt-018f0000-0000-7000-8000-000000000012",
            "identity-verification",
            1,
            payload
        );
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final List<EventEnvelope> envelopes = new ArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            envelopes.add(envelope);
        }
    }
}
