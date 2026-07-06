package com.trainticket.adminaudit.application;

import com.trainticket.adminaudit.application.ports.EventSubscriber;
import com.trainticket.adminaudit.domain.DomainRuleViolation;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class AdminAuditInboundEventHandler implements EventSubscriber.EventHandler {
    private final AdminAuditService service;

    public AdminAuditInboundEventHandler(AdminAuditService service) {
        this.service = Objects.requireNonNull(service, "service is required");
    }

    @Override
    public EventSubscriber.HandlerResult handle(EventEnvelope envelope) {
        if (!"customer-service".equals(envelope.producer()) || !"ManualActionRequested".equals(envelope.eventType())) {
            return EventSubscriber.HandlerResult.SUCCESS;
        }
        try {
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
        } catch (ValidationException | DomainRuleViolation | IllegalArgumentException exception) {
            return EventSubscriber.HandlerResult.FATAL_FAILURE;
        } catch (RuntimeException exception) {
            return EventSubscriber.HandlerResult.TRANSIENT_FAILURE;
        }
    }
}
