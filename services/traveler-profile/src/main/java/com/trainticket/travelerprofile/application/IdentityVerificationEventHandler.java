package com.trainticket.travelerprofile.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.travelerprofile.domain.DomainRuleViolation;
import com.trainticket.travelerprofile.domain.ExternalVerificationFact;
import com.trainticket.travelerprofile.domain.ExternalVerificationStatus;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class IdentityVerificationEventHandler implements EventSubscriber.EventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(IdentityVerificationEventHandler.class);
    private static final String PRODUCER = "identity-verification";

    private final TravelerProfileService travelerProfileService;
    private final ObjectMapper objectMapper;

    public IdentityVerificationEventHandler(TravelerProfileService travelerProfileService, ObjectMapper objectMapper) {
        this.travelerProfileService = Objects.requireNonNull(travelerProfileService, "travelerProfileService is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    @Override
    public EventSubscriber.HandlerResult handle(EventEnvelope envelope) {
        if (!PRODUCER.equals(envelope.producer())) {
            return EventSubscriber.HandlerResult.SUCCESS;
        }
        try {
            return switch (envelope.eventType()) {
                case "CredentialRegistered" -> recordCredentialRegistered(envelope);
                case "VerificationPassed" -> recordVerificationPassed(envelope);
                case "VerificationFailed" -> recordVerificationFailed(envelope);
                default -> EventSubscriber.HandlerResult.SUCCESS;
            };
        } catch (IllegalArgumentException | DomainRuleViolation exception) {
            LOGGER.warn(
                "service=traveler-profile ack-skip identity-verification eventId={} eventType={} reason=CONFORMANT_BUT_UNKNOWN_STATE exceptionClass={}",
                envelope.eventId(), envelope.eventType(), exception.getClass().getSimpleName()
            );
            return EventSubscriber.HandlerResult.SUCCESS;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "service=traveler-profile transient identity-verification eventId={} eventType={} exceptionClass={}",
                envelope.eventId(), envelope.eventType(), exception.getClass().getSimpleName(), exception
            );
            return EventSubscriber.HandlerResult.TRANSIENT_FAILURE;
        }
    }

    private EventSubscriber.HandlerResult recordCredentialRegistered(EventEnvelope envelope) {
        JsonNode payload = payload(envelope);
        return record(envelope, new ExternalVerificationFact(
            requiredText(payload, "credentialRecordId"), null, ExternalVerificationStatus.REGISTERED,
            requiredText(payload, "documentType"), requiredText(payload, "maskedDocumentNo"), requiredText(payload, "documentHash"),
            null, null, null, null, requiredInstant(payload, "registeredAt"), envelope.eventId()
        ));
    }

    private EventSubscriber.HandlerResult recordVerificationPassed(EventEnvelope envelope) {
        JsonNode payload = payload(envelope);
        String verificationStatus = requiredText(payload, "verificationStatus");
        if (!"PASSED".equals(verificationStatus)) {
            throw new IllegalArgumentException("VerificationPassed status was " + verificationStatus);
        }
        return record(envelope, new ExternalVerificationFact(
            requiredText(payload, "credentialRecordId"), requiredText(payload, "verificationCaseId"), ExternalVerificationStatus.PASSED,
            null, null, null, requiredText(payload, "policyVersion"), null, requiredInstant(payload, "validFrom"),
            requiredInstant(payload, "validUntil"), requiredInstant(payload, "completedAt"), envelope.eventId()
        ));
    }

    private EventSubscriber.HandlerResult recordVerificationFailed(EventEnvelope envelope) {
        JsonNode payload = payload(envelope);
        ExternalVerificationStatus status = switch (requiredText(payload, "verificationStatus")) {
            case "FAILED" -> ExternalVerificationStatus.FAILED;
            case "MANUAL_REVIEW_REQUIRED" -> ExternalVerificationStatus.MANUAL_REVIEW_REQUIRED;
            default -> throw new IllegalArgumentException("unknown verificationStatus");
        };
        return record(envelope, new ExternalVerificationFact(
            requiredText(payload, "credentialRecordId"), requiredText(payload, "verificationCaseId"), status,
            null, null, null, requiredText(payload, "policyVersion"), requiredText(payload, "reasonCode"), null,
            null, requiredInstant(payload, "completedAt"), envelope.eventId()
        ));
    }

    private EventSubscriber.HandlerResult record(EventEnvelope envelope, ExternalVerificationFact fact) {
        JsonNode payload = payload(envelope);
        boolean recorded = travelerProfileService.recordIdentityVerificationFact(requiredText(payload, "travelerId"), fact);
        if (!recorded) {
            LOGGER.warn(
                "service=traveler-profile ack-skip identity-verification eventId={} eventType={} travelerId={} reason=TRAVELER_NOT_FOUND",
                envelope.eventId(), envelope.eventType(), requiredText(payload, "travelerId")
            );
        }
        return EventSubscriber.HandlerResult.SUCCESS;
    }

    private JsonNode payload(EventEnvelope envelope) {
        return objectMapper.valueToTree(envelope.payload());
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static Instant requiredInstant(JsonNode node, String field) {
        return Instant.parse(requiredText(node, field));
    }

}
