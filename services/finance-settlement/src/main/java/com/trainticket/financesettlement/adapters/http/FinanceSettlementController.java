package com.trainticket.financesettlement.adapters.http;

import com.trainticket.financesettlement.application.BenefitCostEntry;
import com.trainticket.financesettlement.application.ChannelStatementProjection;
import com.trainticket.financesettlement.application.DomainEventEnvelopeMapper;
import com.trainticket.financesettlement.application.FinanceSettlementApplicationService;
import com.trainticket.financesettlement.application.FinanceSettlementApplicationService.Page;
import com.trainticket.financesettlement.domain.Invoice;
import com.trainticket.financesettlement.domain.ReconciliationBatch;
import com.trainticket.financesettlement.domain.ReconciliationEntry;
import com.trainticket.financesettlement.domain.SupplierSettlement;
import com.trainticket.financesettlement.domain.ReconciliationCase;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    @GetMapping("/api/v1/invoices/{invoiceId}")
    public InvoiceResponse getInvoice(@PathVariable String invoiceId) {
        return InvoiceResponse.from(service.getInvoice(invoiceId));
    }

    @PostMapping("/api/v1/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse generateInvoice(@RequestBody GenerateInvoiceRequest request, HttpServletRequest httpRequest) {
        return InvoiceResponse.from(service.generateInvoice(request.orderId(), (String) httpRequest.getAttribute("X-Correlation-Id")));
    }

    @GetMapping("/api/v1/channel-statements")
    public PagedResponse<ChannelStatementResponse> listChannelStatements(
        @RequestParam(required = false) String channel,
        @RequestParam(required = false) String statementDate,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        var page = service.listChannelStatements(channel, statementDate, limit, offset);
        return new PagedResponse<>(page.items().stream().map(ChannelStatementResponse::from).toList(), page.total(), page.limit(), page.offset());
    }

    @GetMapping("/api/v1/channel-statements/{channelStatementId}")
    public ChannelStatementResponse getChannelStatement(@PathVariable String channelStatementId) {
        return ChannelStatementResponse.from(service.getChannelStatement(channelStatementId));
    }

    @GetMapping("/api/v1/settlements/daily/{date}")
    public DailySettlementResponse getDailySettlement(@PathVariable String date) {
        return DailySettlementResponse.from(service.getDailySettlement(LocalDate.parse(date)));
    }

    @GetMapping("/api/v1/settlements/suppliers/{supplierId}/period")
    public SupplierSettlementResponse getSupplierSettlement(
        @PathVariable String supplierId,
        @RequestParam String startDate,
        @RequestParam String endDate
    ) {
        return SupplierSettlementResponse.from(service.getSupplierSettlement(supplierId, LocalDate.parse(startDate), LocalDate.parse(endDate)));
    }

    @GetMapping("/api/v1/settlements/reconciliation/{batchId}")
    public ReconciliationBatchResponse getReconciliationBatch(@PathVariable String batchId) {
        return ReconciliationBatchResponse.from(service.getReconciliationBatch(batchId));
    }

    @GetMapping("/api/v1/benefit-costs")
    public PagedResponse<BenefitCostResponse> listBenefitCosts(
        @RequestParam(required = false) String accountId,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        Page<BenefitCostEntry> page = service.listBenefitCosts(accountId, limit, offset);
        return new PagedResponse<>(page.items().stream().map(BenefitCostResponse::from).toList(), page.total(), page.limit(), page.offset());
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

    public record ChannelStatementResponse(
        String channelStatementId,
        String channel,
        String statementDate,
        String currency,
        String seedVersion,
        int lineCount,
        Map<String, Object> grossPaymentAmount,
        Map<String, Object> grossRefundAmount,
        Map<String, Object> feeAmount,
        String statementHash,
        String status,
        Instant generatedAt,
        Instant frozenAt,
        String sourceEventId
    ) {
        static ChannelStatementResponse from(ChannelStatementProjection statement) {
            return new ChannelStatementResponse(
                statement.channelStatementId(),
                statement.channel(),
                statement.statementDate(),
                statement.currency(),
                statement.seedVersion(),
                statement.lineCount(),
                DomainEventEnvelopeMapper.moneyPayload(statement.grossPaymentAmount()),
                DomainEventEnvelopeMapper.moneyPayload(statement.grossRefundAmount()),
                DomainEventEnvelopeMapper.moneyPayload(statement.feeAmount()),
                statement.statementHash(),
                statement.status(),
                statement.generatedAt(),
                statement.frozenAt(),
                statement.sourceEventId()
            );
        }
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

    public record DailySettlementResponse(
        String batchId,
        String settlementDate,
        int totalEntries,
        int matchedEntries,
        Object matchRate,
        Map<String, Object> totalVariance,
        int exceptionCount,
        Map<String, Long> statusCounts
    ) {
        static DailySettlementResponse from(ReconciliationBatch batch) {
            return new DailySettlementResponse(
                batch.batchId(),
                batch.settlementDate().toString(),
                batch.report().totalEntries(),
                batch.report().matchedEntries(),
                batch.report().matchRate(),
                DomainEventEnvelopeMapper.moneyPayload(batch.report().totalVariance()),
                batch.report().exceptions().size(),
                batch.statusCounts()
            );
        }
    }

    public record ReconciliationBatchResponse(
        String batchId,
        String settlementDate,
        Instant cutoffAt,
        List<ReconciliationEntryResponse> entries,
        DailySettlementResponse report
    ) {
        static ReconciliationBatchResponse from(ReconciliationBatch batch) {
            return new ReconciliationBatchResponse(
                batch.batchId(),
                batch.settlementDate().toString(),
                batch.cutoffAt(),
                batch.entries().stream().map(ReconciliationEntryResponse::from).toList(),
                DailySettlementResponse.from(batch)
            );
        }
    }

    public record ReconciliationEntryResponse(
        String entryId,
        String orderId,
        String paymentIntentId,
        Map<String, Object> platformAmount,
        Map<String, Object> channelAmount,
        String status,
        Map<String, Object> variance,
        List<String> sourceEventIds
    ) {
        static ReconciliationEntryResponse from(ReconciliationEntry entry) {
            return new ReconciliationEntryResponse(
                entry.entryId(), entry.orderId(), entry.paymentIntentId(),
                DomainEventEnvelopeMapper.moneyPayload(entry.platformAmount()),
                DomainEventEnvelopeMapper.moneyPayload(entry.channelAmount()),
                entry.status().name(),
                DomainEventEnvelopeMapper.moneyPayload(entry.variance()),
                entry.sourceEventIds()
            );
        }
    }

    public record SupplierSettlementResponse(
        String supplierSettlementId,
        String supplierId,
        String periodStartDate,
        String periodEndDate,
        String settlementFrequency,
        Map<String, Object> grossRevenue,
        Map<String, Object> platformCommission,
        Map<String, Object> taxesWithheld,
        Map<String, Object> supplierPayable,
        Map<String, Object> adjustments
    ) {
        static SupplierSettlementResponse from(SupplierSettlement settlement) {
            return new SupplierSettlementResponse(
                settlement.supplierSettlementId(),
                settlement.supplierId(),
                settlement.period().startDate().toString(),
                settlement.period().endDate().toString(),
                settlement.period().frequency().name(),
                DomainEventEnvelopeMapper.moneyPayload(settlement.grossRevenue()),
                DomainEventEnvelopeMapper.moneyPayload(settlement.platformCommission()),
                DomainEventEnvelopeMapper.moneyPayload(settlement.taxesWithheld()),
                DomainEventEnvelopeMapper.moneyPayload(settlement.supplierPayable()),
                DomainEventEnvelopeMapper.moneyPayload(settlement.adjustments())
            );
        }
    }

    public record BenefitCostResponse(
        String benefitId,
        String accountId,
        String issuanceSource,
        String caseId,
        Map<String, Object> amount,
        String eventType,
        Instant occurredAt
    ) {
        static BenefitCostResponse from(BenefitCostEntry entry) {
            return new BenefitCostResponse(
                entry.benefitId(),
                entry.accountId(),
                entry.issuanceSource(),
                entry.caseId(),
                DomainEventEnvelopeMapper.moneyPayload(entry.amount()),
                entry.eventType(),
                entry.occurredAt()
            );
        }
    }

    public record GenerateInvoiceRequest(String orderId) {}

    public record InvoiceResponse(
        String invoiceId,
        String orderId,
        String invoiceNumber,
        Map<String, Object> totalAmount,
        List<String> revenueRecognitionIds,
        Instant generatedAt
    ) {
        static InvoiceResponse from(Invoice invoice) {
            return new InvoiceResponse(
                invoice.invoiceId(),
                invoice.orderId(),
                invoice.invoiceNumber(),
                DomainEventEnvelopeMapper.moneyPayload(invoice.totalAmount()),
                invoice.revenueRecognitionIds(),
                invoice.generatedAt()
            );
        }
    }

    public record PagedResponse<T>(List<T> items, long total, int limit, int offset) {}
}
