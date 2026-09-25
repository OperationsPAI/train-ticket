package com.trainticket.postsales.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trainticket.platformkit.http.CorrelationIds;
import com.trainticket.postsales.application.PostSalesExecutionReconciliationService;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PostSalesExecutionReconciliationControllerTest {
    @Test
    void internalEndpointPassesCaseAndRequestCorrelationToReconciliation() {
        PostSalesExecutionReconciliationService reconciliation = mock(PostSalesExecutionReconciliationService.class);
        PostSalesCase applied = mock(PostSalesCase.class);
        when(applied.caseId()).thenReturn("case-reconciled");
        when(applied.journeyOrderId()).thenReturn("ord-reconciled");
        when(applied.caseType()).thenReturn(PostSalesCaseType.REFUND);
        when(applied.status()).thenReturn(PostSalesCaseStatus.APPLIED);
        when(applied.scope()).thenReturn(new PostSalesScope(
            List.of("sb-reconciled"), List.of("seg-reconciled"),
            List.of("tvl-reconciled"), List.of("ent-reconciled")));
        when(applied.changeOperations()).thenReturn(Map.of());
        when(applied.compensationOperations()).thenReturn(Map.of());
        when(applied.refundPayments()).thenReturn(List.of());
        when(applied.releasedOrderItems()).thenReturn(Set.of("sb-reconciled"));
        when(applied.reasonCode()).thenReturn("CUSTOMER_REQUEST");
        when(applied.actorRef()).thenReturn("acct-reconciled");
        when(applied.terminalReason()).thenReturn("authoritative downstream execution reconciled");
        when(applied.createdAt()).thenReturn(Instant.parse("2026-09-25T08:00:00Z"));
        when(reconciliation.reconcile(eq("psc-case-reconciled"), startsWith("cmd-"), eq("corr-reconciled")))
            .thenReturn(applied);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIds.CORRELATION_ATTRIBUTE, "corr-reconciled");
        PostSalesExecutionReconciliationController controller =
            new PostSalesExecutionReconciliationController(reconciliation);

        Map<String, Object> response = controller.reconcile(
            "psc-case-reconciled", "01890f47-9b7c-7cc2-98c4-dc0c0c073088", request);

        assertEquals("psc-case-reconciled", response.get("caseId"));
        assertEquals("APPLIED", response.get("status"));
        verify(reconciliation).reconcile(eq("psc-case-reconciled"), startsWith("cmd-"), eq("corr-reconciled"));
    }
}
