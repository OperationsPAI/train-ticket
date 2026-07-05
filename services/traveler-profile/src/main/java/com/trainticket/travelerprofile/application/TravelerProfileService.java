package com.trainticket.travelerprofile.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trainticket.travelerprofile.domain.Document;
import com.trainticket.travelerprofile.domain.DocumentAdded;
import com.trainticket.travelerprofile.domain.DocumentType;
import com.trainticket.travelerprofile.domain.DocumentVerified;
import com.trainticket.travelerprofile.domain.EligibilityExpired;
import com.trainticket.travelerprofile.domain.EligibilityGranted;
import com.trainticket.travelerprofile.domain.EligibilityRevoked;
import com.trainticket.travelerprofile.domain.EligibilitySummary;
import com.trainticket.travelerprofile.domain.TravelerProfile;
import com.trainticket.travelerprofile.domain.TravelerProfileEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class TravelerProfileService {
    private static final String PRODUCER = "traveler-profile";

    private final Map<String, StoredTraveler> travelers = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyRecord> idempotencyRecords = new ConcurrentHashMap<>();
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TravelerProfileService(EventPublisher eventPublisher, ObjectMapper objectMapper) {
        this(eventPublisher, objectMapper, Clock.systemUTC());
    }

    TravelerProfileService(EventPublisher eventPublisher, ObjectMapper objectMapper, Clock clock) {
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public TravelerCreatedView create(CreateTravelerCommand command, String requestFingerprint) {
        validateCreate(command);
        String key = scopedKey("POST:/api/v1/travelers", command.idempotencyKey());
        IdempotencyRecord replay = idempotencyRecords.get(key);
        if (replay != null) {
            return replay.responseAs(TravelerCreatedView.class, requestFingerprint);
        }

        Instant now = now();
        String commandId = commandId();
        TravelerProfile aggregate = TravelerProfile.create(
            command.accountId().trim(),
            displayName(command.givenName(), command.familyName()),
            "1970-01-01",
            command.idempotencyKey(),
            now,
            commandId,
            command.correlationId()
        );
        if (command.documentType() != null) {
            aggregate.addDocument(
                toDomainDocumentType(command.documentType()),
                command.documentNumber(),
                "CN",
                now.minus(1, ChronoUnit.DAYS),
                now.plus(3650, ChronoUnit.DAYS),
                displayName(command.givenName(), command.familyName()),
                true,
                now,
                commandId,
                commandId,
                command.correlationId()
            );
        }
        String travelerId = canonicalTravelerId(aggregate.profileId());
        StoredTraveler stored = new StoredTraveler(
            aggregate,
            travelerId,
            "sv-1",
            command.accountId().trim(),
            command.travelerType(),
            command.givenName().trim(),
            command.familyName().trim(),
            command.contactEmail(),
            command.contactPhone(),
            now,
            now
        );
        travelers.put(travelerId, stored);
        publishNewEvents(aggregate.domainEvents(), 0, stored);
        TravelerCreatedView response = new TravelerCreatedView(travelerId, stored.snapshotVersion(), stored.travelerType(), stored.createdAt());
        idempotencyRecords.put(key, IdempotencyRecord.of(requestFingerprint, response));
        return response;
    }

    public TravelerProfileView get(String travelerId) {
        return find(travelerId).toView();
    }

    public TravelerProfileView update(UpdateTravelerCommand command, String requestFingerprint) {
        validateUpdate(command);
        String key = scopedKey("PATCH:/api/v1/travelers/" + command.travelerId(), command.idempotencyKey());
        IdempotencyRecord replay = idempotencyRecords.get(key);
        if (replay != null) {
            return replay.responseAs(TravelerProfileView.class, requestFingerprint);
        }
        StoredTraveler current = find(command.travelerId());
        TravelerProfile aggregate = current.aggregate();
        int eventOffset = aggregate.domainEvents().size();
        Instant now = now();
        String commandId = commandId();

        if (command.documentType() != null) {
            aggregate.addDocument(
                toDomainDocumentType(command.documentType()),
                command.documentNumber(),
                "CN",
                now.minus(1, ChronoUnit.DAYS),
                now.plus(3650, ChronoUnit.DAYS),
                displayName(
                    command.givenName() != null ? command.givenName() : current.givenName(),
                    command.familyName() != null ? command.familyName() : current.familyName()
                ),
                true,
                now,
                commandId,
                commandId,
                command.correlationId()
            );
        }
        updateAggregatePreferences(command, current, aggregate, eventOffset, now, commandId);

        StoredTraveler updated = current.updatedWith(command, nextSnapshotVersion(current.snapshotVersion()), now);
        travelers.put(command.travelerId(), updated);
        publishNewEvents(aggregate.domainEvents(), eventOffset, updated);
        TravelerProfileView response = updated.toView();
        idempotencyRecords.put(key, IdempotencyRecord.of(requestFingerprint, response));
        return response;
    }

    public EligibilityResult determineEligibility(String travelerId, String idempotencyKey, String correlationId, String requestFingerprint) {
        String key = scopedKey("POST:/api/v1/travelers/" + travelerId + "/eligibility", idempotencyKey);
        IdempotencyRecord replay = idempotencyRecords.get(key);
        if (replay != null) {
            return replay.responseAs(EligibilityResult.class, requestFingerprint);
        }
        StoredTraveler traveler = find(travelerId);
        TravelerProfile aggregate = traveler.aggregate();
        int eventOffset = aggregate.domainEvents().size();
        Instant now = now();
        boolean eligible = switch (traveler.travelerType()) {
            case STUDENT, SENIOR, MILITARY, CHILD, INFANT -> true;
            case ADULT -> false;
        };

        EligibilitySummary summary = null;
        String commandId = commandId();
        if (eligible) {
            summary = aggregate.grantEligibility(
                traveler.travelerType().name(),
                "TRAVELER_PROFILE_API",
                fingerprint("eligibility", travelerId, objectMapper.createObjectNode().put("travelerType", traveler.travelerType().name())),
                now,
                now.plus(365, ChronoUnit.DAYS),
                now,
                commandId,
                commandId,
                correlationId
            );
        }
        String eligibilityRef = summary == null ? "elig-" + UUID.randomUUID() : canonicalEligibilityRef(summary.eligibilityId());
        EligibilityResult result = new EligibilityResult(travelerId, eligibilityRef, eligible, now, now.plus(365, ChronoUnit.DAYS));
        publishNewEvents(aggregate.domainEvents(), eventOffset, traveler);
        idempotencyRecords.put(key, IdempotencyRecord.of(requestFingerprint, result));
        return result;
    }

    private StoredTraveler find(String travelerId) {
        StoredTraveler traveler = travelers.get(travelerId);
        if (traveler == null) {
            throw new TravelerNotFoundException(travelerId);
        }
        return traveler;
    }

    private void validateCreate(CreateTravelerCommand command) {
        Map<String, String> errors = new LinkedHashMap<>();
        requireText(command.accountId(), "accountId", errors);
        if (command.travelerType() == null) errors.put("travelerType", "travelerType is required");
        requireText(command.givenName(), "givenName", errors);
        requireText(command.familyName(), "familyName", errors);
        if ((command.documentType() == null) != isBlank(command.documentNumber())) {
            errors.put("document", "documentType and documentNumber must be supplied together");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("Request validation failed", errors);
        }
    }

    private void validateUpdate(UpdateTravelerCommand command) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (command.accountId() != null) requireText(command.accountId(), "accountId", errors);
        if (command.givenName() != null) requireText(command.givenName(), "givenName", errors);
        if (command.familyName() != null) requireText(command.familyName(), "familyName", errors);
        if ((command.documentType() == null) != (command.documentNumber() == null || isBlank(command.documentNumber()))) {
            errors.put("document", "documentType and documentNumber must be supplied together");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("Request validation failed", errors);
        }
    }

    private static void requireText(String value, String field, Map<String, String> errors) {
        if (isBlank(value)) {
            errors.put(field, field + " is required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void updateAggregatePreferences(
        UpdateTravelerCommand command,
        StoredTraveler current,
        TravelerProfile aggregate,
        int eventOffset,
        Instant now,
        String commandId
    ) {
        if (command.accountId() != null && !command.accountId().trim().equals(current.accountId())) {
            aggregate.updatePreference("accountId", command.accountId().trim(), now, commandId, commandId, command.correlationId());
        }
        if (command.travelerType() != null && command.travelerType() != current.travelerType()) {
            aggregate.updatePreference("travelerType", command.travelerType().name(), now, commandId, commandId, command.correlationId());
        }
        if (command.givenName() != null || command.familyName() != null) {
            String nextGiven = command.givenName() != null ? command.givenName().trim() : current.givenName();
            String nextFamily = command.familyName() != null ? command.familyName().trim() : current.familyName();
            String nextDisplayName = displayName(nextGiven, nextFamily);
            if (!nextDisplayName.equals(displayName(current.givenName(), current.familyName()))) {
                aggregate.updatePreference("displayName", nextDisplayName, now, commandId, commandId, command.correlationId());
            }
        }
        if (command.contactEmail() != null && !command.contactEmail().equals(current.contactEmail())) {
            aggregate.updatePreference("contactEmail", command.contactEmail(), now, commandId, commandId, command.correlationId());
        }
        if (command.contactPhone() != null && !command.contactPhone().equals(current.contactPhone())) {
            aggregate.updatePreference("contactPhone", command.contactPhone(), now, commandId, commandId, command.correlationId());
        }
        if (command.documentType() == null && aggregate.domainEvents().size() == eventOffset) {
            aggregate.updatePreference("snapshotTouchedAt", now.toString(), now, commandId, commandId, command.correlationId());
        }
    }

    private void publishNewEvents(List<TravelerProfileEvent> events, int eventOffset, StoredTraveler traveler) {
        for (int index = eventOffset; index < events.size(); index++) {
            publish(events.get(index), traveler);
        }
    }

    private void publish(TravelerProfileEvent event, StoredTraveler traveler) {
        eventPublisher.publish(new EventEnvelope(
            canonicalEventId(event.eventId()),
            externalEventType(event),
            event.occurredAt(),
            canonicalCorrelationId(event.correlationId()),
            canonicalCausationId(event.causationId()),
            PRODUCER,
            event.schemaVersion(),
            payloadFor(event, traveler)
        ));
    }

    private ObjectNode payloadFor(TravelerProfileEvent event, StoredTraveler traveler) {
        if (event instanceof EligibilityGranted granted) {
            return objectMapper.createObjectNode()
                .put("travelerId", traveler.travelerId())
                .put("eligibilityRef", canonicalEligibilityRef(granted.eligibilityId()))
                .put("eligible", true)
                .put("validFrom", granted.validFrom().toString())
                .put("validUntil", granted.validUntil().toString())
                .put("determinedAt", granted.occurredAt().toString());
        }
        if (event instanceof EligibilityExpired expired) {
            return objectMapper.createObjectNode()
                .put("travelerId", traveler.travelerId())
                .put("eligibilityRef", canonicalEligibilityRef(expired.eligibilityId()))
                .put("expiredAt", expired.occurredAt().toString());
        }
        if (event instanceof EligibilityRevoked revoked) {
            return objectMapper.createObjectNode()
                .put("travelerId", traveler.travelerId())
                .put("eligibilityRef", canonicalEligibilityRef(revoked.eligibilityId()))
                .put("revokedAt", revoked.occurredAt().toString())
                .put("reason", revoked.reason());
        }
        if (event instanceof DocumentVerified verified) {
            return objectMapper.createObjectNode()
                .put("travelerId", traveler.travelerId())
                .put("documentId", verified.documentId())
                .put("documentType", verified.documentType().name())
                .put("verifiedAt", verified.occurredAt().toString());
        }
        ObjectNode payload = objectMapper.createObjectNode()
            .put("travelerId", traveler.travelerId())
            .put("snapshotVersion", traveler.snapshotVersion())
            .put("updatedAt", traveler.updatedAt().toString())
            .put("travelerType", traveler.travelerType().name());
        if (traveler.maskedDocumentRef() != null) {
            payload.put("maskedDocumentRef", traveler.maskedDocumentRef());
        }
        if (event instanceof DocumentAdded added) {
            payload.put("documentType", toApiDocumentType(added.documentType()).name());
        }
        return payload;
    }

    private static String externalEventType(TravelerProfileEvent event) {
        if (event instanceof EligibilityGranted || event instanceof EligibilityExpired || event instanceof EligibilityRevoked) {
            return "TravelerEligibilityChanged";
        }
        if (event instanceof DocumentVerified) {
            return "TravelerDocumentVerified";
        }
        return "TravelerProfileUpdated";
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static String displayName(String givenName, String familyName) {
        return givenName.trim() + " " + familyName.trim();
    }

    private static String canonicalTravelerId(String profileId) {
        return profileId.startsWith("tvl-") ? profileId : "tvl-" + profileId;
    }

    private static String canonicalEligibilityRef(String eligibilityId) {
        return eligibilityId.startsWith("elig-") ? eligibilityId : "elig-" + eligibilityId;
    }

    private static String commandId() {
        return canonicalCommandId(UUID.randomUUID().toString());
    }

    private static String canonicalEventId(String id) {
        return id.startsWith("evt-") ? id : "evt-" + id;
    }

    private static String canonicalCommandId(String id) {
        return id.startsWith("cmd-") ? id : "cmd-" + id;
    }

    private static String canonicalCausationId(String id) {
        return id.startsWith("cmd-") || id.startsWith("evt-") ? id : "cmd-" + id;
    }

    private static String canonicalCorrelationId(String id) {
        return id.startsWith("corr-") ? id : "corr-" + id;
    }

    private static String scopedKey(String scope, String idempotencyKey) {
        return scope + ":" + idempotencyKey;
    }

    private static String nextSnapshotVersion(String current) {
        int value = Integer.parseInt(current.substring("sv-".length()));
        return "sv-" + (value + 1);
    }

    private static DocumentType toDomainDocumentType(ApiDocumentType documentType) {
        return switch (documentType) {
            case ID_CARD -> DocumentType.IDENTITY_CARD;
            case PASSPORT -> DocumentType.PASSPORT;
            case OTHER -> DocumentType.OTHER;
        };
    }

    private static ApiDocumentType toApiDocumentType(DocumentType documentType) {
        return switch (documentType) {
            case IDENTITY_CARD -> ApiDocumentType.ID_CARD;
            case PASSPORT -> ApiDocumentType.PASSPORT;
            default -> ApiDocumentType.OTHER;
        };
    }

    public static String fingerprint(String method, String path, com.fasterxml.jackson.databind.JsonNode body) {
        String input = method + " " + path + " " + (body == null ? "" : body.toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private record IdempotencyRecord(String requestFingerprint, Object response) {
        static IdempotencyRecord of(String requestFingerprint, Object response) {
            return new IdempotencyRecord(requestFingerprint, response);
        }

        <T> T responseAs(Class<T> type, String candidateFingerprint) {
            if (!requestFingerprint.equals(candidateFingerprint)) {
                throw new IdempotencyKeyReusedException();
            }
            return type.cast(response);
        }
    }

    private record StoredTraveler(
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
    ) {
        TravelerProfileView toView() {
            return new TravelerProfileView(
                travelerId,
                snapshotVersion,
                accountId,
                travelerType,
                givenName,
                familyName,
                documentType(),
                maskedDocumentRef(),
                contactEmail,
                contactPhone,
                createdAt,
                updatedAt
            );
        }

        StoredTraveler updatedWith(UpdateTravelerCommand command, String nextVersion, Instant now) {
            return new StoredTraveler(
                aggregate,
                travelerId,
                nextVersion,
                command.accountId() != null ? command.accountId().trim() : accountId,
                command.travelerType() != null ? command.travelerType() : travelerType,
                command.givenName() != null ? command.givenName().trim() : givenName,
                command.familyName() != null ? command.familyName().trim() : familyName,
                command.contactEmail() != null ? command.contactEmail() : contactEmail,
                command.contactPhone() != null ? command.contactPhone() : contactPhone,
                createdAt,
                now
            );
        }

        ApiDocumentType documentType() {
            Document document = aggregate.primaryDocument();
            return document == null ? null : toApiDocumentType(document.documentType());
        }

        String maskedDocumentRef() {
            Document document = aggregate.primaryDocument();
            return document == null ? null : document.maskedDocumentRef();
        }
    }
}
