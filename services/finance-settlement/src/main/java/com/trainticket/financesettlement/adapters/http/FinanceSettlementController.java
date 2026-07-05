package com.trainticket.financesettlement.adapters.http;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.financesettlement.application.DomainEventEnvelopeMapper;
import com.trainticket.financesettlement.application.FinanceSettlementApplicationService;
import com.trainticket.financesettlement.application.FinanceSettlementApplicationService.Page;
import com.trainticket.financesettlement.domain.ReconciliationCase;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FinanceSettlementController {
    private final FinanceSettlementApplicationService service;

    public FinanceSettlementController(FinanceSettlementApplicationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/revenue-recognitions/{revenueRecognitionId}")
    public RevenueRecognitionResponse getRevenueRecognition(@PathVariable String revenueRecognitionId) {
        return RevenueRecognitionResponse.from(service.getRevenueRecognition(revenueRecognitionId));
    }

    @GetMapping("/api/v1/reconciliation-cases/{reconciliationCaseId}")
    public ReconciliationCaseResponse getReconciliationCase(@PathVariable String reconciliationCaseId) {
        return ReconciliationCaseResponse.from(service.getReconciliationCase(reconciliationCaseId));
    }

    @GetMapping("/api/v1/reconciliation-cases")
    public PagedResponse<ReconciliationCaseResponse> listReconciliationCases(
        @RequestParam(required = false) String orderId,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        Page<ReconciliationCase> page = service.listReconciliationCases(orderId, limit, offset);
        return new PagedResponse<>(page.items().stream().map(ReconciliationCaseResponse::from).toList(), page.total(), page.limit(), page.offset());
    }

    public record RevenueRecognitionResponse(
        String revenueRecognitionId,
        String orderItemId,
        String orderId,
        String componentCode,
        Map<String, Object> amount,
        String recognitionPolicyVersion,
        Instant recognizedAt
    ) {
        static RevenueRecognitionResponse from(RevenueRecognition recognition) {
            return new RevenueRecognitionResponse(
                recognition.revenueRecognitionId(),
                recognition.orderItemId(),
                recognition.orderId(),
                recognition.componentCode(),
                DomainEventEnvelopeMapper.moneyPayload(recognition.amount()),
                recognition.recognitionPolicyVersion(),
                recognition.recognizedAt()
            );
        }
    }

    public record ReconciliationCaseResponse(
        String reconciliationCaseId,
        String orderId,
        String paymentIntentId,
        String differenceType,
        Map<String, Object> expectedAmount,
        Map<String, Object> actualAmount,
        String description,
        Instant openedAt,
        String status,
        String resolution,
        String resolutionNote
    ) {
        static ReconciliationCaseResponse from(ReconciliationCase reconciliationCase) {
            return new ReconciliationCaseResponse(
                reconciliationCase.reconciliationCaseId(),
                reconciliationCase.orderId(),
                reconciliationCase.paymentIntentId(),
                reconciliationCase.differenceType(),
                DomainEventEnvelopeMapper.moneyPayload(reconciliationCase.expectedAmount()),
                DomainEventEnvelopeMapper.moneyPayload(reconciliationCase.actualAmount()),
                reconciliationCase.description(),
                reconciliationCase.openedAt(),
                reconciliationCase.status().name(),
                reconciliationCase.resolution(),
                reconciliationCase.resolutionNote()
            );
        }
    }

    public record PagedResponse<T>(List<T> items, long total, int limit, int offset) {}
}
