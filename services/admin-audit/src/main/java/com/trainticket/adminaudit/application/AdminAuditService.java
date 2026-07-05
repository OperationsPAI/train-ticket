package com.trainticket.adminaudit.application;

import org.springframework.beans.factory.annotation.Autowired;
import com.trainticket.adminaudit.application.ports.AdminAuditRepository;
import com.trainticket.adminaudit.application.ports.EventEnvelope;
import com.trainticket.adminaudit.application.ports.EventPublisher;
import com.trainticket.adminaudit.domain.AdminAuditEvent;
import com.trainticket.adminaudit.domain.AuditEntry;
import com.trainticket.adminaudit.domain.AuditTrail;
import com.trainticket.adminaudit.domain.DomainRuleViolation;
import com.trainticket.adminaudit.domain.ManualAction;
import com.trainticket.adminaudit.domain.OperatorIdentity;
import com.trainticket.adminaudit.domain.OperatorRef;
import com.trainticket.adminaudit.domain.OperatorRole;
import com.trainticket.adminaudit.domain.PermissionScope;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditService {
    private static final String PRODUCER = "admin-audit";

    private final AdminAuditRepository repository;
    private final EventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public AdminAuditService(AdminAuditRepository repository, EventPublisher eventPublisher) {
        this(repository, eventPublisher, Clock.systemUTC());
    }

    public AdminAuditService(AdminAuditRepository repository, EventPublisher eventPublisher, Clock clock) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public OperatorIdentity registerOperator(
        String email,
        OperatorRole role,
        Set<PermissionScope> scopes,
        String correlationId
    ) {
        if (repository.findOperatorByEmail(email).isPresent()) {
            throw new ConflictException("operator email already registered");
        }
        OperatorIdentity operator = OperatorIdentity.register(
            email,
            role,
            scopes,
            Instant.now(clock),
            newCommandId(),
            normalizeCorrelationId(correlationId)
        );
        repository.saveOperator(operator);
        publish(operator.domainEvents());
        return operator;
    }

    public ManualAction requestManualAction(
        String targetDomain,
        String targetCommand,
        String businessRef,
        String reasonCode,
        String description,
        String requestedByOperatorId,
        boolean requiresApproval,
        String correlationId
    ) {
        OperatorIdentity requester = repository.findOperator(requestedByOperatorId)
            .orElseThrow(() -> new NotFoundException("operator not found"));
        requester.assertActive();
        ManualAction action = ManualAction.request(
            targetDomain,
            targetCommand,
            businessRef,
            reasonCode,
            description,
            new OperatorRef(requester.operatorId(), requester.email()),
            requiresApproval,
            Instant.now(clock),
            newCommandId(),
            normalizeCorrelationId(correlationId)
        );
        repository.saveManualAction(action);
        publish(action.domainEvents());
        recordAudit(
            requester.operatorId(),
            requester.email(),
            "MANUAL_ACTION_REQUESTED",
            businessRef,
            targetDomain,
            reasonCode,
            normalizeCorrelationId(correlationId),
            "Manual action requested: " + action.manualActionId()
        );
        return action;
    }

    public ManualAction approveManualAction(String manualActionId, String approvedByOperatorId, String correlationId) {
        ManualAction action = repository.findManualAction(manualActionId)
            .orElseThrow(() -> new NotFoundException("manual action not found"));
        OperatorIdentity approver = repository.findOperator(approvedByOperatorId)
            .orElseThrow(() -> new NotFoundException("operator not found"));
        int alreadyPublished = action.domainEvents().size();
        try {
            action.approve(
                approver,
                new OperatorRef(approver.operatorId(), approver.email()),
                Instant.now(clock),
                newCommandId(),
                normalizeCorrelationId(correlationId)
            );
        } catch (DomainRuleViolation violation) {
            throw new PreconditionFailedException(violation.getMessage(), violation);
        }
        repository.saveManualAction(action);
        publish(action.domainEvents().subList(alreadyPublished, action.domainEvents().size()));
        recordAudit(
            approver.operatorId(),
            approver.email(),
            "MANUAL_ACTION_APPROVED",
            action.businessRef(),
            action.targetDomain(),
            action.reasonCode(),
            normalizeCorrelationId(correlationId),
            "Manual action approved: " + action.manualActionId()
        );
        return action;
    }

    public OperatorIdentity getOperator(String operatorId) {
        return repository.findOperator(operatorId)
            .orElseThrow(() -> new NotFoundException("operator not found"));
    }

    public Page<AuditEntry> listAuditTrail(String businessRef, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ValidationException("limit must be 1..100 and offset must be >= 0");
        }
        return new Page<>(repository.listAuditEntries(businessRef, limit, offset), repository.countAuditEntries(businessRef), limit, offset);
    }

    private void recordAudit(
        String actorId,
        String actorDisplayName,
        String actionType,
        String resourceRef,
        String resourceDomain,
        String reasonCode,
        String correlationId,
        String resultSummary
    ) {
        AuditTrail trail = new AuditTrail();
        AuditEntry entry = trail.recordEntry(
            actorId,
            actorDisplayName,
            actionType,
            resourceRef,
            resourceDomain,
            reasonCode,
            correlationId,
            resultSummary,
            null,
            Instant.now(clock),
            newCommandId()
        );
        repository.saveAuditEntry(entry);
    }

    private void publish(List<AdminAuditEvent> events) {
        for (AdminAuditEvent event : events) {
            eventPublisher.publish(toContractEnvelope(event));
        }
    }

    public static EventEnvelope toContractEnvelope(AdminAuditEvent event) {
        com.trainticket.adminaudit.domain.EventEnvelope source = event.envelope();
        return new EventEnvelope(
            ensurePrefix(source.eventId(), "evt-"),
            source.eventType(),
            source.occurredAt(),
            ensurePrefix(source.correlationId(), "corr-"),
            ensureCausationPrefix(source.causationId()),
            source.producer(),
            source.schemaVersion(),
            payloadFor(event)
        );
    }

    private static Map<String, Object> payloadFor(AdminAuditEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (java.lang.reflect.RecordComponent component : event.getClass().getRecordComponents()) {
            if ("envelope".equals(component.getName())) {
                continue;
            }
            try {
                payload.put(component.getName(), component.getAccessor().invoke(event));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("could not read event payload", exception);
            }
        }
        return payload;
    }

    private static String normalizeCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return "corr-" + UUID.randomUUID();
        }
        return correlationId.startsWith("corr-") ? correlationId : "corr-" + correlationId;
    }

    private static String ensurePrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value : prefix + value;
    }

    private static String ensureCausationPrefix(String value) {
        if (value.startsWith("cmd-") || value.startsWith("evt-")) {
            return value;
        }
        return "cmd-" + value;
    }

    private static String newCommandId() {
        return "cmd-" + UUID.randomUUID();
    }

    public record Page<T>(List<T> items, int total, int limit, int offset) {}

    public static Set<PermissionScope> parseScopes(List<String> scopes) {
        if (scopes == null) {
            throw new ValidationException("scopes are required");
        }
        try {
            return scopes.stream().map(PermissionScope::valueOf).collect(Collectors.toSet());
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("invalid scope");
        }
    }
}
