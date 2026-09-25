package com.trainticket.postsales.application;

import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import org.springframework.stereotype.Service;

@Service
public final class PostSalesExecutionReconciliationService {
    private final PostSalesApplicationService cases;
    private final PostSalesExecutionEvidenceReader evidenceReader;

    public PostSalesExecutionReconciliationService(
        PostSalesApplicationService cases,
        PostSalesExecutionEvidenceReader evidenceReader
    ) {
        this.cases = cases;
        this.evidenceReader = evidenceReader;
    }

    public PostSalesCase reconcile(String caseId, String sourceCommandId, String correlationId) {
        PostSalesCase current = cases.get(caseId);
        if (current.status() == PostSalesCaseStatus.APPLIED) {
            return current;
        }
        var evidence = evidenceReader.read(current, correlationId);
        return cases.reconcileExecution(evidence, sourceCommandId, correlationId);
    }
}
