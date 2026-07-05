package com.trainticket.adminaudit.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.EnvelopeFactory;
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
            EnvelopeFactory.create("OperatorRegistered", now, sourceCommandId, correlationId, "admin-audit"),
            operatorId,
            email,
            role.name(),
            scopes.stream().map(Enum::name).toList()
        ));
        return identity;
    }

    public String operatorId() { return operatorId; }
    public String email() { return email; }
    public OperatorRole role() { return role; }
    public Set<PermissionScope> scopes() { return scopes; }
    public boolean active() { return active; }
    public Instant registeredAt() { return registeredAt; }
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
}
