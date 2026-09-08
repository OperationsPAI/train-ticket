package com.trainticket.adminaudit.application;

import com.trainticket.adminaudit.application.ports.EventSubscriber;
import com.trainticket.adminaudit.domain.DomainRuleViolation;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AdminAuditInboundEventHandler implements EventSubscriber.EventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminAuditInboundEventHandler.class);

    private final AdminAuditService service;

    public AdminAuditInboundEventHandler(AdminAuditService service) {
        this.service = Objects.requireNonNull(service, "service is required");
    }

    @Override
    public EventSubscriber.HandlerResult handle(EventEnvelope envelope) {
        try {
            if ("customer-service".equals(envelope.producer()) && "ManualActionRequested".equals(envelope.eventType())) {
                InboundEventPayload payload = InboundEventPayload.from(envelope);
                service.recordCustomerManualActionRequest(
                    envelope.eventId(),
                    payload.requiredText("manualActionId"),
                    payload.requiredText("caseId"),
                    payload.requiredText("targetDomain"),
                    payload.requiredText("commandType"),
                    payload.requiredText("operatorRef"),
                    payload.requiredText("reason"),
                    payload.requiredText("description"),
                    payload.requiredBoolean("requiresApproval"),
                    envelope.occurredAt(),
                    envelope.correlationId()
                );
                return EventSubscriber.HandlerResult.SUCCESS;
            }
            if ("legacy-acl".equals(envelope.producer()) && "LegacyCommandMapped".equals(envelope.eventType())) {
                InboundEventPayload payload = InboundEventPayload.from(envelope);
                service.recordLegacyCommandMapped(
                    envelope.eventId(),
                    payload.requiredText("legacyOperation"),
                    payload.requiredText("outcome"),
                    payload.requiredText("operatorRef"),
                    payload.optionalText("reason"),
                    payload.requiredText("sourceRef"),
                    envelope.correlationId()
                );
                return EventSubscriber.HandlerResult.SUCCESS;
            }
            return EventSubscriber.HandlerResult.SUCCESS;
        } catch (ValidationException | DomainRuleViolation | IllegalArgumentException exception) {
            // FATAL_FAILURE dead-letters the event: it will not be retried, and the
            // fact it carried is dropped. That is the right call for a malformed
            // payload, but doing it without a log line means an audit record is lost
            // with no trace of why -- in an audit context specifically, that is the
            // worst possible thing to do silently.
            LOGGER.error("admin-audit REJECTING event={} eventId={} producer={} as unprocessable "
                    + "-- dead-lettering it, so this audit fact is permanently dropped.",
                envelope.eventType(), envelope.eventId(), envelope.producer(), exception);
            return EventSubscriber.HandlerResult.FATAL_FAILURE;
        } catch (RuntimeException exception) {
            LOGGER.error("admin-audit handler FAILED event={} eventId={} producer={} "
                    + "-- returning TRANSIENT_FAILURE, so this message stays pending and will be "
                    + "redelivered. If this repeats for the same eventId the consumer is stuck.",
                envelope.eventType(), envelope.eventId(), envelope.producer(), exception);
            return EventSubscriber.HandlerResult.TRANSIENT_FAILURE;
        }
    }
}
