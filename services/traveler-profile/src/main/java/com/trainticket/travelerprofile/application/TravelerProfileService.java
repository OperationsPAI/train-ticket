package com.trainticket.travelerprofile.application;

import com.fasterxml.jackson.databind.ObjectMapper;
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

        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String commandId = commandId();
        TravelerProfile aggregate = TravelerProfile.create(
            command.accountId(),
            displayName(command.givenName(), command.familyName()),
            "1970-01-01",
            command.idempotencyKey(),
            now,
            commandId,
            command.correlationId()
        );
        String travelerId = canonicalTravelerId(aggregate.profileId());
        StoredTraveler stored = new StoredTraveler(
            travelerId,
            "sv-1",
            command.accountId().trim(),
            command.travelerType(),
            command.givenName().trim(),
            command.familyName().trim(),
            command.documentType(),
            command.documentNumber(),
            command.contactEmail(),
            command.contactPhone(),
            now,
            now
        );
        travelers.put(travelerId, stored);
        publish(aggregate.domainEvents().getLast(), stored);
        TravelerCreatedView response = new TravelerCreatedView(travelerId, stored.snapshotVersion(), stored.travelerType(), stored.createdAt());
        idempotencyRecords.put(key, IdempotencyRecord.of(requestFingerprint, response, objectMapper));
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
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        StoredTraveler updated = current.updatedWith(command, nextSnapshotVersion(current.snapshotVersion()), now);
        travelers.put(command.travelerId(), updated);
        publishProfileUpdated(updated, command.correlationId(), commandId());
        TravelerProfileView response = updated.toView();
        idempotencyRecords.put(key, IdempotencyRecord.of(requestFingerprint, response, objectMapper));
        return response;
    }

    public EligibilityResult determineEligibility(String travelerId, String idempotencyKey, String correlationId, String requestFingerprint) {
        String key = scopedKey("POST:/api/v1/travelers/" + travelerId + "/eligibility", idempotencyKey);
        IdempotencyRecord replay = idempotencyRecords.get(key);
        if (replay != null) {
            return replay.responseAs(EligibilityResult.class, requestFingerprint);
        }
        StoredTraveler traveler = find(travelerId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        boolean eligible = switch (traveler.travelerType()) {
            case STUDENT, SENIOR, MILITARY, CHILD, INFANT -> true;
            case ADULT -> false;
        };
        String eligibilityRef = "elig-" + UUID.randomUUID();
        EligibilityResult result = new EligibilityResult(travelerId, eligibilityRef, eligible, now, now.plus(365, ChronoUnit.DAYS));
        publishEligibilityChanged(traveler, result, correlationId, commandId());
        idempotencyRecords.put(key, IdempotencyRecord.of(requestFingerprint, result, objectMapper));
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

    private void publish(TravelerProfileEvent event, StoredTraveler traveler) {
        eventPublisher.publish(new EventEnvelope(
            canonicalEventId(event.eventId()),
            "TravelerProfileUpdated",
            event.occurredAt(),
            canonicalCorrelationId(event.correlationId()),
            canonicalCommandId(event.causationId()),
            PRODUCER,
            event.schemaVersion(),
            objectMapper.valueToTree(Map.of(
                "travelerId", traveler.travelerId(),
                "snapshotVersion", traveler.snapshotVersion(),
                "travelerType", traveler.travelerType().name(),
                "updatedAt", traveler.updatedAt().toString()
            ))
        ));
    }

    private void publishProfileUpdated(StoredTraveler traveler, String correlationId, String causationId) {
        eventPublisher.publish(new EventEnvelope(
            canonicalEventId(UUID.randomUUID().toString()),
            "TravelerProfileUpdated",
            clock.instant().truncatedTo(ChronoUnit.MILLIS),
            canonicalCorrelationId(correlationId),
            canonicalCommandId(causationId),
            PRODUCER,
            1,
            objectMapper.valueToTree(traveler.toView())
        ));
    }

    private void publishEligibilityChanged(StoredTraveler traveler, EligibilityResult result, String correlationId, String causationId) {
        eventPublisher.publish(new EventEnvelope(
            canonicalEventId(UUID.randomUUID().toString()),
            "TravelerEligibilityChanged",
            clock.instant().truncatedTo(ChronoUnit.MILLIS),
            canonicalCorrelationId(correlationId),
            canonicalCommandId(causationId),
            PRODUCER,
            1,
            objectMapper.valueToTree(Map.of(
                "travelerId", traveler.travelerId(),
                "eligibilityRef", result.eligibilityRef(),
                "eligible", result.eligible(),
                "validFrom", result.validFrom().toString(),
                "validUntil", result.validUntil().toString()
            ))
        ));
    }

    private static String displayName(String givenName, String familyName) {
        return givenName.trim() + " " + familyName.trim();
    }

    private static String canonicalTravelerId(String profileId) {
        return "tvl-" + profileId;
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
        static IdempotencyRecord of(String requestFingerprint, Object response, ObjectMapper objectMapper) {
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
        String travelerId,
        String snapshotVersion,
        String accountId,
        TravelerType travelerType,
        String givenName,
        String familyName,
        ApiDocumentType documentType,
        String documentNumber,
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
                documentType,
                mask(documentNumber),
                contactEmail,
                contactPhone,
                createdAt,
                updatedAt
            );
        }

        StoredTraveler updatedWith(UpdateTravelerCommand command, String nextVersion, Instant now) {
            return new StoredTraveler(
                travelerId,
                nextVersion,
                command.accountId() != null ? command.accountId().trim() : accountId,
                command.travelerType() != null ? command.travelerType() : travelerType,
                command.givenName() != null ? command.givenName().trim() : givenName,
                command.familyName() != null ? command.familyName().trim() : familyName,
                command.documentType() != null ? command.documentType() : documentType,
                command.documentNumber() != null ? command.documentNumber() : documentNumber,
                command.contactEmail() != null ? command.contactEmail() : contactEmail,
                command.contactPhone() != null ? command.contactPhone() : contactPhone,
                createdAt,
                now
            );
        }

        private static String mask(String documentNumber) {
            if (documentNumber == null || documentNumber.isBlank()) {
                return null;
            }
            if (documentNumber.length() <= 4) {
                return "***" + documentNumber;
            }
            return documentNumber.substring(0, Math.min(2, documentNumber.length())) + "***" + documentNumber.substring(documentNumber.length() - 4);
        }
    }

}
