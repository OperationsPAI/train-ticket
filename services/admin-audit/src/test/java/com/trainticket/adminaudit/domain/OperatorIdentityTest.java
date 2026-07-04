package com.trainticket.adminaudit.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperatorIdentityTest {

    @Test
    void registerOperator() {
        Instant now = Instant.now();
        OperatorIdentity op = OperatorIdentity.register(
            "admin@example.com",
            OperatorRole.ADMIN,
            Set.of(PermissionScope.ORDER_READ, PermissionScope.ORDER_WRITE, PermissionScope.AUDIT_READ),
            now,
            "cmd-" + UUID.randomUUID(),
            "corr-" + UUID.randomUUID()
        );

        assertNotNull(op.operatorId());
        assertTrue(op.operatorId().startsWith("op-"));
        assertEquals("admin@example.com", op.email());
        assertEquals(OperatorRole.ADMIN, op.role());
        assertTrue(op.active());
        assertEquals(3, op.scopes().size());
        assertTrue(op.scopes().contains(PermissionScope.ORDER_READ));
        assertTrue(op.scopes().contains(PermissionScope.AUDIT_READ));
        assertEquals(1, op.domainEvents().size());
        assertTrue(op.domainEvents().get(0) instanceof OperatorRegistered);
        OperatorRegistered evt = (OperatorRegistered) op.domainEvents().get(0);
        assertEquals(op.operatorId(), evt.operatorId());
        assertEquals("admin@example.com", evt.email());
    }

    @Test
    void assertActivePassesForActiveOperator() {
        OperatorIdentity op = OperatorIdentity.register(
            "op@test.com",
            OperatorRole.OPERATOR,
            Set.of(PermissionScope.ORDER_READ),
            Instant.now(),
            "cmd-1",
            "corr-1"
        );
        assertDoesNotThrow(op::assertActive);
    }

    @Test
    void assertHasScopePassesWhenScopePresent() {
        OperatorIdentity op = OperatorIdentity.register(
            "op@test.com",
            OperatorRole.OPERATOR,
            Set.of(PermissionScope.ORDER_READ, PermissionScope.ORDER_WRITE),
            Instant.now(),
            "cmd-1",
            "corr-1"
        );
        assertDoesNotThrow(() -> op.assertHasScope(PermissionScope.ORDER_WRITE));
    }

    @Test
    void assertHasScopeThrowsWhenScopeMissing() {
        OperatorIdentity op = OperatorIdentity.register(
            "op@test.com",
            OperatorRole.OPERATOR,
            Set.of(PermissionScope.ORDER_READ),
            Instant.now(),
            "cmd-1",
            "corr-1"
        );
        DomainRuleViolation ex = assertThrows(DomainRuleViolation.class,
            () -> op.assertHasScope(PermissionScope.AUDIT_READ));
        assertTrue(ex.getMessage().contains("lacks required scope"));
    }

    @Test
    void assertNotOperatorThrowsForSelf() {
        OperatorIdentity op = OperatorIdentity.register(
            "op@test.com",
            OperatorRole.OPERATOR,
            Set.of(PermissionScope.ORDER_READ),
            Instant.now(),
            "cmd-1",
            "corr-1"
        );
        OperatorRef selfRef = new OperatorRef(op.operatorId(), "self");
        DomainRuleViolation ex = assertThrows(DomainRuleViolation.class,
            () -> op.assertNotOperator(selfRef));
        assertTrue(ex.getMessage().contains("cannot act on self"));
    }

    @Test
    void assertNotOperatorPassesForDifferentOperator() {
        OperatorIdentity op = OperatorIdentity.register(
            "op@test.com",
            OperatorRole.OPERATOR,
            Set.of(PermissionScope.ORDER_READ),
            Instant.now(),
            "cmd-1",
            "corr-1"
        );
        OperatorRef other = new OperatorRef("op-other-123", "Other");
        assertDoesNotThrow(() -> op.assertNotOperator(other));
    }

    @Test
    void registerThrowsOnEmptyEmail() {
        assertThrows(NullPointerException.class,
            () -> OperatorIdentity.register(
                null, OperatorRole.ADMIN, Set.of(PermissionScope.ORDER_READ),
                Instant.now(), "cmd-1", "corr-1"));
    }

    @Test
    void registerThrowsOnEmptyScopes() {
        DomainRuleViolation ex = assertThrows(DomainRuleViolation.class,
            () -> OperatorIdentity.register(
                "op@test.com", OperatorRole.ADMIN, Set.of(),
                Instant.now(), "cmd-1", "corr-1"));
        assertTrue(ex.getMessage().contains("at least one permission scope"));
    }

    @Test
    void registerThrowsOnNullNow() {
        assertThrows(NullPointerException.class,
            () -> OperatorIdentity.register(
                "op@test.com", OperatorRole.ADMIN, Set.of(PermissionScope.ORDER_READ),
                null, "cmd-1", "corr-1"));
    }

    @Test
    void eventEnvelopeHasCorrectProducer() {
        OperatorIdentity op = OperatorIdentity.register(
            "op@test.com",
            OperatorRole.OPERATOR,
            Set.of(PermissionScope.ORDER_READ),
            Instant.now(),
            "cmd-1",
            "corr-1"
        );
        OperatorRegistered evt = (OperatorRegistered) op.domainEvents().get(0);
        assertEquals("admin-audit", evt.envelope().producer());
        assertTrue(evt.envelope().eventId().startsWith("evt-"));
        assertEquals(1, evt.envelope().schemaVersion());
    }

    @Test
    void operatorRegisteredEventCarriesScopes() {
        OperatorIdentity op = OperatorIdentity.register(
            "op@test.com",
            OperatorRole.ADMIN,
            Set.of(PermissionScope.ORDER_READ, PermissionScope.AUDIT_READ),
            Instant.now(),
            "cmd-1",
            "corr-1"
        );
        OperatorRegistered evt = (OperatorRegistered) op.domainEvents().get(0);
        assertEquals(2, evt.scopes().size());
        assertTrue(evt.scopes().contains("ORDER_READ"));
        assertTrue(evt.scopes().contains("AUDIT_READ"));
        assertEquals("ADMIN", evt.role());
    }
}
