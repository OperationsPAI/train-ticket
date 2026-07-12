package com.trainticket.adminaudit.application;

import org.springframework.beans.factory.annotation.Autowired;
import com.trainticket.adminaudit.application.ports.AdminAuditRepository;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
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
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
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

        int afterApprovalPublished = action.domainEvents().size();
        try {
            action.execute(
                "Manual action execution recorded",
                Instant.now(clock),
                newCommandId(),
                normalizeCorrelationId(correlationId)
            );
        } catch (DomainRuleViolation violation) {
            throw new PreconditionFailedException(violation.getMessage(), violation);
        }
        repository.saveManualAction(action);
        publish(action.domainEvents().subList(afterApprovalPublished, action.domainEvents().size()));
        recordAudit(
            approver.operatorId(),
            approver.email(),
            "MANUAL_ACTION_EXECUTED",
            action.businessRef(),
            action.targetDomain(),
            action.reasonCode(),
            normalizeCorrelationId(correlationId),
            action.resultSummary()
        );
        return action;
    }

    public ManualAction recordCustomerManualActionRequest(
        String sourceEventId,
        String manualActionId,
        String caseId,
        String targetDomain,
        String commandType,
        String operatorRef,
        String reason,
        String description,
        boolean requiresApproval,
        Instant requestedAt,
        String correlationId
    ) {
        if (repository.findManualAction(manualActionId).isPresent()) {
            return repository.findManualAction(manualActionId).orElseThrow();
        }
        ManualAction action = ManualAction.requestWithId(
            manualActionId,
            targetDomain,
            commandType,
            caseId,
            reason,
            description,
            new OperatorRef(operatorRef, operatorRef),
            requiresApproval,
            requestedAt,
            sourceEventId,
            normalizeCorrelationId(correlationId)
        );
        repository.saveManualAction(action);
        recordAudit(
            operatorRef,
            operatorRef,
            "MANUAL_ACTION_REQUEST_INTAKEN",
            caseId,
            targetDomain,
            reason,
            normalizeCorrelationId(correlationId),
            "Manual action intake recorded: " + manualActionId
        );
        return action;
    }

    public void recordLegacyCommandMapped(
        String sourceEventId,
        String legacyOperation,
        String outcome,
        String operatorRef,
        String reason,
        String sourceRef,
        String correlationId
    ) {
        String resourceRef = sourceRef == null || sourceRef.isBlank() ? sourceEventId : sourceRef;
        recordAudit(
            operatorRef,
            operatorRef,
            "LEGACY_COMMAND_MAPPED",
            resourceRef,
            "legacy-acl",
            reason == null || reason.isBlank() ? legacyOperation : reason,
            normalizeCorrelationId(correlationId),
            "Legacy command mapped: " + legacyOperation + " " + outcome
        );
    }

    public ManualAction rejectManualAction(String manualActionId, String rejectedByOperatorId, String reason, String correlationId) {
        ManualAction action = repository.findManualAction(manualActionId)
            .orElseThrow(() -> new NotFoundException("manual action not found"));
        OperatorRef rejectedBy = repository.findOperator(rejectedByOperatorId)
            .map(operator -> new OperatorRef(operator.operatorId(), operator.email()))
            .orElseGet(() -> new OperatorRef(rejectedByOperatorId, rejectedByOperatorId));
        int alreadyPublished = action.domainEvents().size();
        try {
            action.reject(rejectedBy, reason, Instant.now(clock), newCommandId(), normalizeCorrelationId(correlationId));
        } catch (DomainRuleViolation violation) {
            throw new PreconditionFailedException(violation.getMessage(), violation);
        }
        repository.saveManualAction(action);
        publish(action.domainEvents().subList(alreadyPublished, action.domainEvents().size()));
        recordAudit(
            rejectedBy.operatorId(),
            rejectedBy.displayName(),
            "MANUAL_ACTION_REJECTED",
            action.businessRef(),
            action.targetDomain(),
            action.reasonCode(),
            normalizeCorrelationId(correlationId),
            "Manual action rejected: " + action.manualActionId()
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
        String sourceCommandId = newCommandId();
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
        eventPublisher.publish(toContractEnvelope(new com.trainticket.adminaudit.domain.AuditEntryRecorded(
            createAuditEnvelope(correlationId, sourceCommandId),
            entry.entryId(),
            entry.actorId(),
            entry.actorDisplayName(),
            entry.actionType(),
            entry.resourceRef(),
            entry.resourceDomain(),
            entry.reasonCode(),
            entry.correlationId(),
            entry.resultSummary(),
            entry.correctedEntryId()
        )));
    }

    private EventEnvelope createAuditEnvelope(String correlationId, String causationId) {
        return new EventEnvelope(
            PrefixedIds.newEventId(),
            "AuditEntryRecorded",
            Instant.now(clock),
            normalizeCorrelationId(correlationId),
            ensureCausationPrefix(causationId),
            PRODUCER,
            1,
            Map.of()
        );
    }

    private static String actorId(ManualAction action) {
        return action.approvedBy() != null ? action.approvedBy().operatorId() : action.requestedBy().operatorId();
    }

    private static String actorDisplayName(ManualAction action) {
        return action.approvedBy() != null ? action.approvedBy().displayName() : action.requestedBy().displayName();
    }

    private void publish(List<AdminAuditEvent> events) {
        for (AdminAuditEvent event : events) {
            eventPublisher.publish(toContractEnvelope(event));
        }
    }

    public static EventEnvelope toContractEnvelope(AdminAuditEvent event) {
        com.trainticket.platformkit.messaging.EventEnvelope source = event.envelope();
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
                Object value = component.getAccessor().invoke(event);
                if (value != null) {
                    payload.put(component.getName(), value);
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("could not read event payload", exception);
            }
        }
        return payload;
    }

    private static String normalizeCorrelationId(String correlationId) {
        if (PrefixedIds.isCorrelationId(correlationId)) {
            return correlationId;
        }
        return PrefixedIds.newCorrelationId();
    }

    private static String ensurePrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value : prefix + value;
    }

    private static String ensureCausationPrefix(String value) {
        if (PrefixedIds.isCausationId(value)) {
            return value;
        }
        return PrefixedIds.newCommandId();
    }

    private static String newCommandId() {
        return PrefixedIds.newCommandId();
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
