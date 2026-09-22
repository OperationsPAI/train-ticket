package com.trainticket.postsales.api;

import com.trainticket.postsales.application.PostSalesConflictReconciler;
import com.trainticket.postsales.application.PostSalesMapper;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/internal/post-sales-cases")
public class PostSalesReconciliationController {
    private final PostSalesConflictReconciler reconciler;
    public PostSalesReconciliationController(PostSalesConflictReconciler reconciler) { this.reconciler = reconciler; }

    @PostMapping("/{caseId}/reconcile-conflict")
    public Map<String,Object> reconcile(@PathVariable String caseId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value="X-Correlation-Id",required=false) String correlation) {
        return PostSalesMapper.caseDetails(reconciler.reconcile(caseId,correlation));
    }
}
