package com.trainticket.adminaudit.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate root for operator identity.
 * Each operator must be bound to a real person or system account,
 * have a defined role and permission scopes, and cannot perform
 * actions after suspension or deactivation.
 */
public final class OperatorIdentity {
    private final String operatorId;
    private final String email;
    private final OperatorRole role;
    private final Set<PermissionScope> scopes;
    private final boolean active;
    private final Instant registeredAt;
    private long version;
    private final List<AdminAuditEvent> domainEvents;

    private OperatorIdentity(
        String operatorId,
        String email,
        OperatorRole role,
        Set<PermissionScope> scopes,
        boolean active,
        Instant registeredAt
    ) {
        this.operatorId = requireText(operatorId, "operatorId");
        this.email = requireText(email, "email");
        this.role = Objects.requireNonNull(role, "role is required");
        this.scopes = Collections.unmodifiableSet(Objects.requireNonNull(scopes, "scopes are required"));
        this.active = active;
        this.registeredAt = Objects.requireNonNull(registeredAt, "registeredAt is required");
        this.domainEvents = new ArrayList<>();
        requireInvariants();
    }

    public static OperatorIdentity register(
        String email,
        OperatorRole role,
        Set<PermissionScope> scopes,
        Instant now,
        String sourceCommandId,
        String correlationId
    ) {
        Objects.requireNonNull(now, "now is required");
        String operatorId = "op-" + UUID.randomUUID().toString();
        OperatorIdentity identity = new OperatorIdentity(operatorId, email, role, scopes, true, now);
        identity.domainEvents.add(new OperatorRegistered(
            createEnvelope("OperatorRegistered", now, sourceCommandId, correlationId),
            operatorId,
            email,
            role.name(),
            scopes.stream().map(Enum::name).toList()
        ));
        return identity;
    }


    public static OperatorIdentity rehydrate(
        String operatorId,
        String email,
        OperatorRole role,
        Set<PermissionScope> scopes,
        boolean active,
        Instant registeredAt,
        List<AdminAuditEvent> domainEvents
    ) {
        OperatorIdentity identity = new OperatorIdentity(operatorId, email, role, scopes, active, registeredAt);
        identity.domainEvents.clear();
        identity.domainEvents.addAll(Objects.requireNonNull(domainEvents, "domainEvents are required"));
        return identity;
    }

    public String operatorId() { return operatorId; }
    public String email() { return email; }
    public OperatorRole role() { return role; }
    public Set<PermissionScope> scopes() { return scopes; }
    public boolean active() { return active; }
    public Instant registeredAt() { return registeredAt; }
    public long version() { return version; }
    public OperatorIdentity withVersion(long version) {
        if (version < 0) {
            throw new DomainRuleViolation("version must not be negative");
        }
        this.version = version;
        return this;
    }
    public List<AdminAuditEvent> domainEvents() { return List.copyOf(domainEvents); }

    public void assertActive() {
        if (!active) {
            throw new DomainRuleViolation("operator " + operatorId + " is not active");
        }
    }

    public void assertHasScope(PermissionScope required) {
        if (!scopes.contains(required)) {
            throw new DomainRuleViolation("operator " + operatorId + " lacks required scope " + required);
        }
    }

    public void assertNotOperator(OperatorRef other) {
        if (operatorId.equals(other.operatorId())) {
            throw new DomainRuleViolation("operator " + operatorId + " cannot act on self");
        }
    }

    private void requireInvariants() {
        if (scopes.isEmpty()) {
            throw new DomainRuleViolation("operator must have at least one permission scope");
        }
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
    private static EventEnvelope createEnvelope(String eventType, Instant occurredAt, String causationId, String correlationId) {
        return new EventEnvelope(
            PrefixedIds.newEventId(),
            eventType,
            occurredAt,
            canonicalCorrelationId(correlationId),
            canonicalCausationId(causationId),
            "admin-audit",
            1,
            java.util.Map.of()
        );
    }

    private static String canonicalCorrelationId(String correlationId) {
        return PrefixedIds.isCorrelationId(correlationId)
            ? correlationId
            : PrefixedIds.newCorrelationId();
    }

    private static String canonicalCausationId(String causationId) {
        if (PrefixedIds.isCausationId(causationId)) {
            return causationId;
        }
        return PrefixedIds.newCommandId();
    }

}
