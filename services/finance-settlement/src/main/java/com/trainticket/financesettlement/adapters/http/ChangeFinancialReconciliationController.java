package com.trainticket.financesettlement.adapters.http;

import com.trainticket.financesettlement.application.FinanceSettlementEventHandler;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class ChangeFinancialReconciliationController {
    private final FinanceSettlementEventHandler handler;
    public ChangeFinancialReconciliationController(FinanceSettlementEventHandler handler) { this.handler=handler; }

    @PostMapping("/api/v1/internal/change-financial-reconciliations/{caseId}")
    public Map<String,String> reconcile(@PathVariable String caseId,@RequestHeader("X-Correlation-Id") String correlation) {
        handler.reconcileChange(caseId,correlation);
        return Map.of("caseId",caseId,"status","RECONCILED");
    }
}
