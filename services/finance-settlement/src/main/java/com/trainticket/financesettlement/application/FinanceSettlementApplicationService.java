package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.DomainRuleViolation;
import com.trainticket.financesettlement.domain.EventMetadata;
import com.trainticket.financesettlement.domain.FinanceSettlementEvent;
import com.trainticket.financesettlement.domain.Invoice;
import com.trainticket.financesettlement.domain.ReconciliationBatch;
import com.trainticket.financesettlement.domain.ReconciliationEntry;
import com.trainticket.financesettlement.domain.SettlementFrequency;
import com.trainticket.financesettlement.domain.SettlementPeriod;
import com.trainticket.financesettlement.domain.SupplierSettlement;
import com.trainticket.financesettlement.domain.RevenueAllocation;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.ReconciliationCase;
import com.trainticket.financesettlement.domain.ReconciliationCompleted;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.time.Clock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FinanceSettlementApplicationService {
    private final RevenueRecognitionRepository revenueRecognitions;
    private final ReconciliationCaseRepository reconciliationCases;
    private final InvoiceRepository invoices;
    private final FinanceSettlementProjectionRepository projections;
    private final ReconciliationBatchRepository reconciliationBatches;
    private final SupplierSettlementRepository supplierSettlements;
    private final EventPublisher eventPublisher;
    private final DomainEventEnvelopeMapper envelopeMapper;
    private final Clock clock;

    public FinanceSettlementApplicationService(
        RevenueRecognitionRepository revenueRecognitions,
        ReconciliationCaseRepository reconciliationCases,
        EventPublisher eventPublisher,
        DomainEventEnvelopeMapper envelopeMapper
    ) {
        this(revenueRecognitions, reconciliationCases, new InMemoryInvoiceRepository(), new InMemoryFinanceSettlementProjectionRepository(), new InMemoryReconciliationBatchRepository(), new InMemorySupplierSettlementRepository(), eventPublisher, envelopeMapper, Clock.systemUTC());
    }

    public FinanceSettlementApplicationService(
        RevenueRecognitionRepository revenueRecognitions,
        ReconciliationCaseRepository reconciliationCases,
        InvoiceRepository invoices,
        EventPublisher eventPublisher,
        DomainEventEnvelopeMapper envelopeMapper,
        Clock clock
    ) {
        this(revenueRecognitions, reconciliationCases, invoices, new InMemoryFinanceSettlementProjectionRepository(), new InMemoryReconciliationBatchRepository(), new InMemorySupplierSettlementRepository(), eventPublisher, envelopeMapper, clock);
    }

    public FinanceSettlementApplicationService(
        RevenueRecognitionRepository revenueRecognitions,
        ReconciliationCaseRepository reconciliationCases,
        InvoiceRepository invoices,
        FinanceSettlementProjectionRepository projections,
        EventPublisher eventPublisher,
        DomainEventEnvelopeMapper envelopeMapper,
        Clock clock
    ) {
        this(revenueRecognitions, reconciliationCases, invoices, projections, new InMemoryReconciliationBatchRepository(), new InMemorySupplierSettlementRepository(), eventPublisher, envelopeMapper, clock);
    }

    public FinanceSettlementApplicationService(
        RevenueRecognitionRepository revenueRecognitions,
        ReconciliationCaseRepository reconciliationCases,
        InvoiceRepository invoices,
        FinanceSettlementProjectionRepository projections,
        ReconciliationBatchRepository reconciliationBatches,
        SupplierSettlementRepository supplierSettlements,
        EventPublisher eventPublisher,
        DomainEventEnvelopeMapper envelopeMapper,
        Clock clock
    ) {
        this.revenueRecognitions = revenueRecognitions;
        this.reconciliationCases = reconciliationCases;
        this.invoices = invoices;
        this.projections = projections;
        this.reconciliationBatches = reconciliationBatches;
        this.supplierSettlements = supplierSettlements;
        this.eventPublisher = eventPublisher;
        this.envelopeMapper = envelopeMapper;
        this.clock = clock;
    }

    public RevenueRecognition getRevenueRecognition(String revenueRecognitionId) {
        return revenueRecognitions.findById(revenueRecognitionId)
            .orElseThrow(() -> new ResourceNotFoundException("revenue recognition not found"));
    }

    public ReconciliationCase getReconciliationCase(String reconciliationCaseId) {
        return reconciliationCases.findById(reconciliationCaseId)
            .orElseThrow(() -> new ResourceNotFoundException("reconciliation case not found"));
    }

    public Invoice getInvoice(String invoiceId) {
        return invoices.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("invoice not found"));
    }

    public ReconciliationBatch getReconciliationBatch(String batchId) {
        return reconciliationBatches.findById(batchId)
            .orElseThrow(() -> new ResourceNotFoundException("reconciliation batch not found"));
    }

    public ReconciliationBatch getDailySettlement(LocalDate settlementDate) {
        return reconciliationBatches.findBySettlementDate(settlementDate)
            .orElseThrow(() -> new ResourceNotFoundException("daily settlement not found"));
    }

    public SupplierSettlement getSupplierSettlement(String supplierId, LocalDate startDate, LocalDate endDate) {
        return supplierSettlements.findBySupplierAndPeriod(requireText(supplierId, "supplierId"), startDate, endDate)
            .orElseThrow(() -> new ResourceNotFoundException("supplier settlement not found"));
    }

    List<RevenueRecognition> findRevenueRecognitionsByOrderId(String orderId) {
        return revenueRecognitions.findByOrderId(orderId);
    }

    public Page<ReconciliationCase> listReconciliationCases(String orderId, int limit, int offset) {
        if (limit < 1 || limit > 100) {
            throw new ValidationException("limit must be between 1 and 100");
        }
        if (offset < 0) {
            throw new ValidationException("offset must not be negative");
        }
        List<ReconciliationCase> items = reconciliationCases.find(orderId, limit, offset);
        return new Page<>(items, reconciliationCases.count(orderId), limit, offset);
    }

    public ChannelStatementProjection getChannelStatement(String channelStatementId) {
        return projections.findChannelStatement(channelStatementId)
            .orElseThrow(() -> new ResourceNotFoundException("channel statement not found"));
    }

    public Page<ChannelStatementProjection> listChannelStatements(String channel, String statementDate, int limit, int offset) {
        if (limit < 1 || limit > 100) {
            throw new ValidationException("limit must be between 1 and 100");
        }
        if (offset < 0) {
            throw new ValidationException("offset must not be negative");
        }
        if ((channel == null || channel.isBlank()) && (statementDate == null || statementDate.isBlank())) {
            throw new ValidationException("channel or statementDate is required");
        }
        int normalizedLimit = limit;
        int normalizedOffset = offset;
        return new Page<>(
            projections.findChannelStatements(channel, statementDate, normalizedLimit, normalizedOffset),
            projections.countChannelStatements(channel, statementDate),
            normalizedLimit,
            normalizedOffset
        );
    }

    public Page<BenefitCostEntry> listBenefitCosts(String accountId, int limit, int offset) {
        if (limit < 1 || limit > 100) {
            throw new ValidationException("limit must be between 1 and 100");
        }
        if (offset < 0) {
            throw new ValidationException("offset must not be negative");
        }
        List<BenefitCostEntry> items = projections.findBenefitCostEntries(accountId, limit, offset);
        return new Page<>(items, projections.countBenefitCostEntries(accountId), limit, offset);
    }

    public Invoice generateInvoice(String orderId, String correlationId) {
        requireText(orderId, "orderId");
        return invoices.findByOrderId(orderId).orElseGet(() -> {
            List<RevenueRecognition> recognitions = revenueRecognitions.findByOrderId(orderId);
            if (recognitions.isEmpty()) {
                throw new DomainRuleViolation("invoice requires recognized revenue for order");
            }
            Money total = recognitions.stream()
                .map(RevenueRecognition::netAmount)
                .reduce(Money::plus)
                .orElseThrow();
            Invoice invoice = Invoice.generate(
                orderId,
                total,
                recognitions.stream().map(RevenueRecognition::revenueRecognitionId).toList(),
                clock.instant(),
                PrefixedIds.newCommandId(),
                canonicalCorrelationId(correlationId)
            );
            invoices.save(invoice);
            publish(invoice.domainEvents());
            return invoice;
        });
    }

    public ReconciliationBatch runDailyReconciliation(LocalDate settlementDate, String correlationId) {
        LocalDate date = settlementDate == null ? LocalDate.now(clock).minusDays(1) : settlementDate;
        return reconciliationBatches.findBySettlementDate(date).orElseGet(() -> {
            Currency[] currency = {Currency.getInstance("CNY")};
            Map<String, FinanceSettlementEventHandler.PaymentCaptureFact> captures = new LinkedHashMap<>();
            for (FinanceSettlementEventHandler.PaymentCaptureFact capture : projections.findCapturesForSettlementDate(date)) {
                captures.put(capture.orderId().isBlank() ? capture.paymentIntentId() : capture.orderId(), capture);
                currency[0] = capture.amount().currency();
            }
            Map<String, ChannelStatementProjection> statements = new LinkedHashMap<>();
            for (ChannelStatementProjection statement : projections.findChannelStatementsForSettlementDate(date)) {
                statements.put(statement.channelStatementId(), statement);
                currency[0] = Currency.getInstance(statement.currency());
            }
            List<ReconciliationEntry> entries = new ArrayList<>();
            for (var item : captures.entrySet()) {
                ChannelStatementProjection statement = statements.remove(item.getKey());
                Money channelAmount = statement == null ? Money.zero(item.getValue().amount().currency()) : statement.grossPaymentAmount().minus(statement.grossRefundAmount());
                entries.add(ReconciliationEntry.compare("re-" + item.getKey(), item.getKey(), item.getValue().paymentIntentId(), item.getValue().amount(), channelAmount, true, statement != null, List.of(item.getValue().sourceEventId())));
            }
            for (var item : statements.entrySet()) {
                Money channelAmount = item.getValue().grossPaymentAmount().minus(item.getValue().grossRefundAmount());
                entries.add(ReconciliationEntry.compare("re-" + item.getKey(), item.getKey(), "", Money.zero(channelAmount.currency()), channelAmount, false, true, List.of(item.getValue().sourceEventId())));
            }
            ReconciliationBatch batch = ReconciliationBatch.complete(date, date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), currency[0], entries,
                clock.instant(), PrefixedIds.newCommandId(), PrefixedIds.newCommandId(), canonicalCorrelationId(correlationId));
            reconciliationBatches.save(batch);
            publish(batch.domainEvents());
            return batch;
        });
    }

    public SupplierSettlement calculateSupplierSettlement(String supplierId, LocalDate startDate, LocalDate endDate, SettlementFrequency frequency,
                                                          BigDecimal commissionPct, BigDecimal withholdingPct, String correlationId) {
        requireText(supplierId, "supplierId");
        SettlementPeriod period = new SettlementPeriod(startDate, endDate, frequency == null ? SettlementFrequency.DAILY : frequency);
        return supplierSettlements.findBySupplierAndPeriod(supplierId, startDate, endDate).orElseGet(() -> {
            Currency currency = Currency.getInstance("CNY");
            List<RevenueAllocation> allocations = revenueRecognitions.findByOrderId(supplierId).stream()
                .filter(recognition -> !recognition.recognizedAt().atZone(ZoneOffset.UTC).toLocalDate().isBefore(startDate))
                .filter(recognition -> !recognition.recognizedAt().atZone(ZoneOffset.UTC).toLocalDate().isAfter(endDate))
                .map(recognition -> RevenueAllocation.calculate(recognition.orderId(), recognition.netAmount(), commissionPct, withholdingPct, Money.zero(recognition.amount().currency())))
                .toList();
            SupplierSettlement settlement = SupplierSettlement.calculate(supplierId, period, commissionPct, withholdingPct, allocations, currency,
                clock.instant(), PrefixedIds.newCommandId(), PrefixedIds.newCommandId(), canonicalCorrelationId(correlationId));
            supplierSettlements.save(settlement);
            publish(settlement.domainEvents());
            return settlement;
        });
    }

    public String describeWiring() {
        return revenueRecognitions.getClass().getSimpleName() + "/" + reconciliationCases.getClass().getSimpleName()
            + "/" + invoices.getClass().getSimpleName() + "/" + eventPublisher.getClass().getSimpleName();
    }

    public void saveAndPublish(RevenueRecognition recognition) {
        saveAndPublish(recognition, 0);
    }

    public void saveAndPublish(RevenueRecognition recognition, int firstUnpublishedEventIndex) {
        revenueRecognitions.save(recognition);
        publish(recognition.domainEvents().subList(firstUnpublishedEventIndex, recognition.domainEvents().size()));
    }

    public void saveAndPublish(ReconciliationCase reconciliationCase) {
        reconciliationCases.save(reconciliationCase);
        publish(reconciliationCase.domainEvents());
    }

    public void saveAndPublish(SupplierSettlement settlement) {
        supplierSettlements.save(settlement);
        publish(settlement.domainEvents());
    }

    public void publishReconciliationCompleted(String orderId, String paymentIntentId, Money expectedAmount, Money actualAmount,
                                               List<String> matchedRevenueRecognitionIds, List<String> sourceEventIds,
                                               String status, Instant now, String causationId, String correlationId) {
        ReconciliationCompleted completed = new ReconciliationCompleted(
            "rec-" + com.trainticket.platformkit.idempotency.UuidV7.generate(),
            orderId,
            paymentIntentId == null ? "" : paymentIntentId,
            status,
            expectedAmount,
            actualAmount,
            matchedRevenueRecognitionIds,
            sourceEventIds,
            EventMetadata.create(now, PrefixedIds.newCommandId(), causationId, canonicalCorrelationId(correlationId),
                Map.of("orderId", orderId, "reconciliationStatus", status))
        );
        publish(List.of(completed));
    }

    private void publish(List<FinanceSettlementEvent> events) {
        for (FinanceSettlementEvent event : events) {
            try {
                eventPublisher.publish(envelopeMapper.toEnvelope(event));
            } catch (PublishFailedException | DomainRuleViolation ex) {
                throw ex;
            }
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " is required");
        }
        return value;
    }

    private static String canonicalCorrelationId(String correlationId) {
        return PrefixedIds.isCorrelationId(correlationId) ? correlationId : PrefixedIds.newCorrelationId();
    }

    public record Page<T>(List<T> items, long total, int limit, int offset) {}
}
