package com.trainticket.adminaudit.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManualActionTest {

    private final OperatorRef requester = new OperatorRef("op-requester-1", "Alice");
    private final OperatorRef approver = new OperatorRef("op-approver-1", "Bob");

    private OperatorIdentity createApproverIdentity() {
        return OperatorIdentity.register(
            "bob@example.com",
            OperatorRole.ADMIN,
            Set.of(PermissionScope.ORDER_READ, PermissionScope.ORDER_WRITE, PermissionScope.OPERATOR_WRITE),
            Instant.now(),
            "cmd-1",
            "corr-1"
        );
    }

    @Test
    void requestManualAction() {
        Instant now = Instant.now();
        ManualAction action = ManualAction.request(
            "journey-order",
            "RequestManualOrderAction",
            "ord-123",
            "CUSTOMER_REQUEST",
            "Cancel order per customer request",
            requester,
            true,
            now,
            "cmd-" + UUID.randomUUID(),
            "corr-" + UUID.randomUUID()
        );

        assertNotNull(action.manualActionId());
        assertTrue(action.manualActionId().startsWith("ma-"));
        assertEquals("journey-order", action.targetDomain());
        assertEquals("RequestManualOrderAction", action.targetCommand());
        assertEquals("ord-123", action.businessRef());
        assertEquals("CUSTOMER_REQUEST", action.reasonCode());
        assertEquals(ManualActionState.REQUESTED, action.state());
        assertTrue(action.requiresApproval());
        assertEquals(1, action.domainEvents().size());
        assertTrue(action.domainEvents().get(0) instanceof ManualActionRequested);
        ManualActionRequested evt = (ManualActionRequested) action.domainEvents().get(0);
        assertEquals(action.manualActionId(), evt.manualActionId());
        assertEquals(requester.operatorId(), evt.requestedByOperatorId());
    }

    @Test
    void requestWithoutApproval() {
        Instant now = Instant.now();
        ManualAction action = ManualAction.request(
            "journey-order",
            "RequestManualOrderAction",
            "ord-123",
            "ROUTINE",
            "Simple status check",
            requester,
            false,
            now,
            "cmd-1",
            "corr-1"
        );
        assertFalse(action.requiresApproval());
    }

    @Test
    void approveManualAction() {
        Instant now = Instant.now();
        ManualAction action = createRequestedAction(now);
        OperatorIdentity approverIdentity = createApproverIdentity();

        action.approve(approverIdentity, approver, now.plusSeconds(60), "cmd-approve", "corr-1");

        assertEquals(ManualActionState.APPROVED, action.state());
        assertEquals(approver.operatorId(), action.approvedBy().operatorId());
        assertNotNull(action.approvedAt());
        assertEquals(2, action.domainEvents().size());
        assertTrue(action.domainEvents().get(1) instanceof ManualActionApproved);
        ManualActionApproved evt = (ManualActionApproved) action.domainEvents().get(1);
        assertEquals(action.manualActionId(), evt.manualActionId());
        assertEquals(approver.operatorId(), evt.approvedByOperatorId());
    }

    @Test
    void approveRequiresDistinctOperator() {
        Instant now = Instant.now();
        ManualAction action = createRequestedAction(now);
        OperatorIdentity requesterIdentity = OperatorIdentity.register(
            "alice@example.com",
            OperatorRole.ADMIN,
            Set.of(PermissionScope.ORDER_READ, PermissionScope.ORDER_WRITE, PermissionScope.OPERATOR_WRITE),
            now,
            "cmd-1",
            "corr-1"
        );

        DomainRuleViolation ex = assertThrows(DomainRuleViolation.class,
            () -> action.approve(requesterIdentity, requester, now.plusSeconds(60), "cmd-approve", "corr-1"));
        assertTrue(ex.getMessage().contains("cannot approve their own action"));
    }

    @Test
    void approveRequiresOperatorWriteScopeForHighRisk() {
        Instant now = Instant.now();
        ManualAction action = createRequestedAction(now);
        OperatorIdentity limitedApprover = OperatorIdentity.register(
            "charlie@example.com",
            OperatorRole.OPERATOR,
            Set.of(PermissionScope.ORDER_READ), // lacks OPERATOR_WRITE
            now,
            "cmd-1",
            "corr-1"
        );
        OperatorRef limitedRef = new OperatorRef(limitedApprover.operatorId(), "Charlie");

        DomainRuleViolation ex = assertThrows(DomainRuleViolation.class,
            () -> action.approve(limitedApprover, limitedRef, now.plusSeconds(60), "cmd-approve", "corr-1"));
        assertTrue(ex.getMessage().contains("lacks required scope"));
    }

    @Test
    void rejectManualAction() {
        Instant now = Instant.now();
        ManualAction action = createRequestedAction(now);
        OperatorRef rejectedBy = new OperatorRef("op-rejector-1", "Carol");

        action.reject(rejectedBy, "Policy violation", now.plusSeconds(60), "cmd-reject", "corr-1");

        assertEquals(ManualActionState.REJECTED, action.state());
        assertEquals("Policy violation", action.rejectionReason());
        assertEquals(2, action.domainEvents().size());
        assertTrue(action.domainEvents().get(1) instanceof ManualActionRejected);
        ManualActionRejected evt = (ManualActionRejected) action.domainEvents().get(1);
        assertEquals(rejectedBy.operatorId(), evt.rejectedByOperatorId());
    }

    @Test
    void executeApprovedAction() {
        Instant now = Instant.now();
        ManualAction action = createRequestedAction(now);
        OperatorIdentity approverIdentity = createApproverIdentity();
        action.approve(approverIdentity, approver, now.plusSeconds(60), "cmd-approve", "corr-1");

        action.execute("Order cancelled successfully", now.plusSeconds(120), "cmd-execute", "corr-1");

        assertEquals(ManualActionState.EXECUTED, action.state());
        assertEquals("Order cancelled successfully", action.resultSummary());
        assertEquals(3, action.domainEvents().size());
        assertTrue(action.domainEvents().get(2) instanceof ManualActionExecuted);
        ManualActionExecuted evt = (ManualActionExecuted) action.domainEvents().get(2);
        assertEquals(action.manualActionId(), evt.manualActionId());
    }

    @Test
    void executeWithoutApprovalThrowsWhenRequired() {
        Instant now = Instant.now();
        ManualAction action = createRequestedAction(now);
        // Not approved yet

        DomainRuleViolation ex = assertThrows(DomainRuleViolation.class,
            () -> action.execute("Should fail", now.plusSeconds(120), "cmd-execute", "corr-1"));
        assertTrue(ex.getMessage().contains("expected manual action state APPROVED"));
    }

    @Test
    void markFailedForTargetRejection() {
        Instant now = Instant.now();
        ManualAction action = createRequestedAction(now);
        OperatorIdentity approverIdentity = createApproverIdentity();
        action.approve(approverIdentity, approver, now.plusSeconds(60), "cmd-approve", "corr-1");

        action.markFailed("Target domain rejected: inventory conflict", now.plusSeconds(120), "cmd-execute", "corr-1");

        assertEquals(ManualActionState.FAILED, action.state());
        assertTrue(action.resultSummary().contains("inventory conflict"));
    }

    @Test
    void approveThrowsForWrongState() {
        Instant now = Instant.now();
        ManualAction action = createRequestedAction(now);
        OperatorIdentity approverIdentity = createApproverIdentity();
        action.approve(approverIdentity, approver, now.plusSeconds(60), "cmd-approve", "corr-1");

        // Already approved - approving again should throw
        DomainRuleViolation ex = assertThrows(DomainRuleViolation.class,
            () -> action.approve(approverIdentity, approver, now.plusSeconds(120), "cmd-approve2", "corr-1"));
        assertTrue(ex.getMessage().contains("expected manual action state REQUESTED"));
    }

    @Test
    void rejectThrowsForWrongState() {
        Instant now = Instant.now();
        ManualAction action = createRequestedAction(now);
        OperatorIdentity approverIdentity = createApproverIdentity();
        action.approve(approverIdentity, approver, now.plusSeconds(60), "cmd-approve", "corr-1");
        OperatorRef rejector = new OperatorRef("op-reject-1", "Dave");

        DomainRuleViolation ex = assertThrows(DomainRuleViolation.class,
            () -> action.reject(rejector, "Too late", now.plusSeconds(120), "cmd-reject", "corr-1"));
        assertTrue(ex.getMessage().contains("expected manual action state REQUESTED"));
    }

    @Test
    void executeThrowsForRejectedAction() {
        Instant now = Instant.now();
        ManualAction action = createRequestedAction(now);
        action.reject(new OperatorRef("op-rejector-1", "Carol"), "Not needed", now.plusSeconds(60), "cmd-reject", "corr-1");

        DomainRuleViolation ex = assertThrows(DomainRuleViolation.class,
            () -> action.execute("Should fail", now.plusSeconds(120), "cmd-execute", "corr-1"));
        assertTrue(ex.getMessage().contains("expected manual action state APPROVED"));
    }

    @Test
    void eventEnvelopeHasCorrectProducer() {
        Instant now = Instant.now();
        ManualAction action = createRequestedAction(now);
        ManualActionRequested evt = (ManualActionRequested) action.domainEvents().get(0);
        assertEquals("admin-audit", evt.envelope().producer());
        assertTrue(evt.envelope().eventId().startsWith("evt-"));
        assertEquals(1, evt.envelope().schemaVersion());
    }

    @Test
    void manualActionRejectedEventContainsReason() {
        Instant now = Instant.now();
        ManualAction action = createRequestedAction(now);
        OperatorRef rejector = new OperatorRef("op-rejector-1", "Carol");
        action.reject(rejector, "Insufficient justification", now.plusSeconds(60), "cmd-reject", "corr-1");
        ManualActionRejected evt = (ManualActionRejected) action.domainEvents().get(1);
        assertEquals("Insufficient justification", evt.reason());
    }

    @Test
    void manualActionForNonApprovalDoesNotRequireOperatorWriteScope() {
        Instant now = Instant.now();
        ManualAction action = ManualAction.request(
            "journey-order",
            "ViewOrderStatus",
            "ord-456",
            "ROUTINE_CHECK",
            "Routine status check",
            requester,
            false, // does not require approval
            now,
            "cmd-1",
            "corr-1"
        );
        OperatorIdentity viewer = OperatorIdentity.register(
            "dave@example.com",
            OperatorRole.OPERATOR,
            Set.of(PermissionScope.ORDER_READ),
            now,
            "cmd-1",
            "corr-1"
        );
        OperatorRef viewerRef = new OperatorRef(viewer.operatorId(), "Dave");

        // Should approve even without OPERATOR_WRITE scope because requiresApproval is false
        action.approve(viewer, viewerRef, now.plusSeconds(60), "cmd-approve", "corr-1");
        assertEquals(ManualActionState.APPROVED, action.state());
    }

    private ManualAction createRequestedAction(Instant now) {
        return ManualAction.request(
            "journey-order",
            "RequestManualOrderAction",
            "ord-123",
            "CUSTOMER_REQUEST",
            "Cancel order per customer request",
            requester,
            true,
            now,
            "cmd-" + UUID.randomUUID(),
            "corr-" + UUID.randomUUID()
        );
    }
}
