package com.trainticket.postsales.api;

import com.trainticket.platformkit.http.CorrelationIds;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.postsales.application.PostSalesExecutionReconciliationService;
import com.trainticket.postsales.application.PostSalesMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/post-sales-cases")
public final class PostSalesExecutionReconciliationController {
    private final PostSalesExecutionReconciliationService reconciliation;

    public PostSalesExecutionReconciliationController(PostSalesExecutionReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping("/{caseId}/reconcile-execution")
    public Map<String, Object> reconcile(
        @PathVariable String caseId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        HttpServletRequest request
    ) {
        return PostSalesMapper.caseDetails(reconciliation.reconcile(
            caseId, PrefixedIds.newCommandId(), CorrelationIds.from(request)));
    }
}
