package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import org.junit.jupiter.api.Test;

class PostSalesExecutionReconciliationServiceTest {
    @Test
    void appliedCaseReturnsWithoutReadingAnyDownstreamEvidence() {
        PostSalesApplicationService cases = mock(PostSalesApplicationService.class);
        PostSalesExecutionEvidenceReader evidenceReader = mock(PostSalesExecutionEvidenceReader.class);
        PostSalesCase applied = mock(PostSalesCase.class);
        when(applied.status()).thenReturn(PostSalesCaseStatus.APPLIED);
        when(cases.get("psc-applied")).thenReturn(applied);
        PostSalesExecutionReconciliationService service =
            new PostSalesExecutionReconciliationService(cases, evidenceReader);

        assertSame(applied, service.reconcile("psc-applied", "cmd-reconcile", "corr-reconcile"));

        verifyNoInteractions(evidenceReader);
        verify(cases, never()).reconcileExecution(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
