package com.trainticket.adminaudit.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperatorRefTest {

    @Test
    void createOperatorRef() {
        OperatorRef ref = new OperatorRef("op-123", "Alice");
        assertEquals("op-123", ref.operatorId());
        assertEquals("Alice", ref.displayName());
    }

    @Test
    void operatorIdMustNotBeBlank() {
        assertThrows(DomainRuleViolation.class,
            () -> new OperatorRef("", "Alice"));
    }

    @Test
    void displayNameMustNotBeBlank() {
        assertThrows(DomainRuleViolation.class,
            () -> new OperatorRef("op-123", ""));
    }

    @Test
    void operatorIdMustNotBeNull() {
        assertThrows(NullPointerException.class,
            () -> new OperatorRef(null, "Alice"));
    }

    @Test
    void equality() {
        OperatorRef ref1 = new OperatorRef("op-123", "Alice");
        OperatorRef ref2 = new OperatorRef("op-123", "Alice");
        OperatorRef ref3 = new OperatorRef("op-456", "Bob");
        assertEquals(ref1, ref2);
        assertNotEquals(ref1, ref3);
    }
}
