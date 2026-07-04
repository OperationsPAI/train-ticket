package com.trainticket.adminaudit.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ApprovalTest {

    @Test
    void createGrantedApproval() {
        Instant now = Instant.now();
        Approval approval = new Approval("op-approver-1", "Bob", now, true, null);
        assertEquals("op-approver-1", approval.approvedByOperatorId());
        assertTrue(approval.granted());
        assertNull(approval.rejectionReason());
    }

    @Test
    void createRejectedApproval() {
        Instant now = Instant.now();
        Approval approval = new Approval("op-rejector-1", "Carol", now, false, "Policy violation");
        assertFalse(approval.granted());
        assertEquals("Policy violation", approval.rejectionReason());
    }

    @Test
    void grantedApprovalHasNoRejectionReason() {
        Instant now = Instant.now();
        Approval approval = new Approval("op-approver-1", "Bob", now, true, null);
        assertNull(approval.rejectionReason());
    }

    @Test
    void rejectedApprovalHasReason() {
        Instant now = Instant.now();
        Approval approval = new Approval("op-rejector-1", "Carol", now, false, "Insufficient justification");
        assertEquals("Insufficient justification", approval.rejectionReason());
    }
}
