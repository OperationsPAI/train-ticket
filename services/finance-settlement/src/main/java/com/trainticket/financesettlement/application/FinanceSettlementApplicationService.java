package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.DomainRuleViolation;
import com.trainticket.financesettlement.domain.EventMetadata;
import com.trainticket.financesettlement.domain.FeeAccrual;
import com.trainticket.financesettlement.domain.FinanceSettlementEvent;
import com.trainticket.financesettlement.domain.Invoice;
import com.trainticket.financesettlement.domain.ReconciliationBatch;
import com.trainticket.financesettlement.domain.ReconciliationEntry;
import com.trainticket.financesettlement.domain.SettlementFrequency;
import com.trainticket.financesettlement.domain.SettlementPeriod;
import com.trainticket.financesettlement.domain.SupplierSettlement;
import com.trainticket.financesettlement.domain.TaxCalculation;
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
import java.util.Objects;

public class FinanceSettlementApplicationService {
    private final RevenueRecognitionRepository revenueRecognitions;
    private final ReconciliationCaseRepository reconciliationCases;
    private final InvoiceRepository invoices;
    private final FinanceSettlementProjectionRepository projections;
    private final ReconciliationBatchRepository reconciliationBatches;
    private static final BigDecimal PLATFORM_SERVICE_FEE_PCT = new BigDecimal("0.02");
    private static final BigDecimal SUPPLIER_SERVICE_FEE_PCT = new BigDecimal("0.01");
    private static final BigDecimal VOLUNTARY_CANCELLATION_RETAINED_FEE_PCT = new BigDecimal("0.10");

    private final SupplierSettlementRepository supplierSettlements;
    private final FeeAccrualRepository feeAccruals;
    private final EventPublisher eventPublisher;
    private final DomainEventEnvelopeMapper envelopeMapper;
    private final Clock clock;

    public FinanceSettlementApplicationService(
        RevenueRecognitionRepository revenueRecognitions,
        ReconciliationCaseRepository reconciliationCases,
        EventPublisher eventPublisher,
        DomainEventEnvelopeMapper envelopeMapper
    ) {
        this(revenueRecognitions, reconciliationCases, new InMemoryInvoiceRepository(), new InMemoryFinanceSettlementProjectionRepository(), new InMemoryReconciliationBatchRepository(), new InMemorySupplierSettlementRepository(), new InMemoryFeeAccrualRepository(), eventPublisher, envelopeMapper, Clock.systemUTC());
    }

    public FinanceSettlementApplicationService(
        RevenueRecognitionRepository revenueRecognitions,
        ReconciliationCaseRepository reconciliationCases,
        InvoiceRepository invoices,
        EventPublisher eventPublisher,
        DomainEventEnvelopeMapper envelopeMapper,
        Clock clock
    ) {
        this(revenueRecognitions, reconciliationCases, invoices, new InMemoryFinanceSettlementProjectionRepository(), new InMemoryReconciliationBatchRepository(), new InMemorySupplierSettlementRepository(), new InMemoryFeeAccrualRepository(), eventPublisher, envelopeMapper, clock);
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
        this(revenueRecognitions, reconciliationCases, invoices, projections, new InMemoryReconciliationBatchRepository(), new InMemorySupplierSettlementRepository(), new InMemoryFeeAccrualRepository(), eventPublisher, envelopeMapper, clock);
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
        this(revenueRecognitions, reconciliationCases, invoices, projections, reconciliationBatches, supplierSettlements, new InMemoryFeeAccrualRepository(), eventPublisher, envelopeMapper, clock);
    }

    public FinanceSettlementApplicationService(
        RevenueRecognitionRepository revenueRecognitions,
        ReconciliationCaseRepository reconciliationCases,
        InvoiceRepository invoices,
        FinanceSettlementProjectionRepository projections,
        ReconciliationBatchRepository reconciliationBatches,
        SupplierSettlementRepository supplierSettlements,
        FeeAccrualRepository feeAccruals,
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
        this.feeAccruals = feeAccruals;
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
            Map<String, PlatformReconciliationFact> captures = new LinkedHashMap<>();
            for (FinanceSettlementEventHandler.PaymentCaptureFact capture : projections.findCapturesForSettlementDate(date)) {
                String key = reconciliationKey(capture.orderId(), capture.paymentIntentId());
                captures.merge(key, new PlatformReconciliationFact(capture.orderId(), capture.paymentIntentId(), capture.amount(), List.of(capture.sourceEventId())), PlatformReconciliationFact::merge);
                currency[0] = capture.amount().currency();
            }
            Map<String, ChannelReconciliationFact> channelLines = new LinkedHashMap<>();
            for (ChannelStatementLineProjection line : projections.findChannelStatementLinesForSettlementDate(date)) {
                String key = reconciliationKey(line.orderId(), line.paymentIntentId());
                channelLines.merge(key, new ChannelReconciliationFact(line.orderId(), line.paymentIntentId(), line.actualAmount(), List.of(line.sourceEventId())), ChannelReconciliationFact::merge);
                currency[0] = line.actualAmount().currency();
            }
            if (channelLines.isEmpty()) {
                for (ChannelStatementProjection statement : projections.findChannelStatementsForSettlementDate(date)) {
                    Money channelAmount = statement.grossPaymentAmount().minus(statement.grossRefundAmount());
                    channelLines.put(statement.channelStatementId(), new ChannelReconciliationFact(statement.channelStatementId(), "", channelAmount, List.of(statement.sourceEventId())));
                    currency[0] = Currency.getInstance(statement.currency());
                }
            }
            List<ReconciliationEntry> entries = new ArrayList<>();
            for (var item : captures.entrySet()) {
                ChannelReconciliationFact channel = channelLines.remove(item.getKey());
                Money channelAmount = channel == null ? Money.zero(item.getValue().amount().currency()) : channel.amount();
                List<String> sourceEventIds = new ArrayList<>(item.getValue().sourceEventIds());
                if (channel != null) {
                    sourceEventIds.addAll(channel.sourceEventIds());
                }
                entries.add(ReconciliationEntry.compare("re-" + item.getKey(), item.getValue().orderId(), item.getValue().paymentIntentId(), item.getValue().amount(), channelAmount, true, channel != null, sourceEventIds));
            }
            for (var item : channelLines.entrySet()) {
                entries.add(ReconciliationEntry.compare("re-" + item.getKey(), item.getValue().orderId(), item.getValue().paymentIntentId(), Money.zero(item.getValue().amount().currency()), item.getValue().amount(), false, true, item.getValue().sourceEventIds()));
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
            List<RevenueAllocation> allocations = revenueRecognitions.findBySupplierAndPeriod(supplierId, startDate, endDate).stream()
                .map(recognition -> RevenueAllocation.calculate(recognition.orderId(), recognition.netAmount(), commissionPct, withholdingPct, refundAdjustmentsForOrder(recognition.orderId(), recognition.amount().currency())))
                .toList();
            if (!allocations.isEmpty()) {
                currency = allocations.getFirst().grossRevenue().currency();
            }
            SupplierSettlement settlement = SupplierSettlement.calculate(supplierId, period, commissionPct, withholdingPct, allocations, currency,
                clock.instant(), PrefixedIds.newCommandId(), PrefixedIds.newCommandId(), canonicalCorrelationId(correlationId));
            supplierSettlements.save(settlement);
            publish(settlement.domainEvents());
            return settlement;
        });
    }

    public FeeAccrual accrueFeesForPaymentCapture(String orderId, Money capturedAmount, Money refundedAmount, String sourceEventId, Instant now, String causationId, String correlationId) {
        requireText(orderId, "orderId");
        if (feeAccruals.findByOrderId(orderId).stream().anyMatch(accrual -> accrual.sourceEventId().equals(sourceEventId))) {
            return feeAccruals.findByOrderId(orderId).stream().filter(accrual -> accrual.sourceEventId().equals(sourceEventId)).findFirst().orElseThrow();
        }
        Money platformServiceFee = RevenueAllocation.multiply(capturedAmount, PLATFORM_SERVICE_FEE_PCT);
        Money supplierServiceFee = RevenueAllocation.multiply(capturedAmount, SUPPLIER_SERVICE_FEE_PCT);
        Money serviceFees = platformServiceFee.plus(supplierServiceFee);
        TaxCalculation tax = TaxCalculation.calculate(serviceFees, capturedAmount, capturedAmount.minus(platformServiceFee), BigDecimal.ZERO, refundedAmount);
        FeeAccrual accrual = FeeAccrual.accrue(orderId, platformServiceFee, supplierServiceFee, Money.zero(capturedAmount.currency()), tax, sourceEventId, now, PrefixedIds.newCommandId(), causationId, canonicalCorrelationId(correlationId));
        feeAccruals.save(accrual);
        publish(accrual.domainEvents());
        return accrual;
    }

    public FeeAccrual accrueFeesForRefund(String orderId, Money refundAmount, Currency currency, String sourceEventId, Instant now, String causationId, String correlationId) {
        requireText(orderId, "orderId");
        if (feeAccruals.findByOrderId(orderId).stream().anyMatch(accrual -> accrual.sourceEventId().equals(sourceEventId))) {
            return feeAccruals.findByOrderId(orderId).stream().filter(accrual -> accrual.sourceEventId().equals(sourceEventId)).findFirst().orElseThrow();
        }
        Currency effectiveCurrency = Objects.requireNonNull(currency, "currency is required");
        Money zero = Money.zero(effectiveCurrency);
        Money retainedCancellationFee = RevenueAllocation.multiply(refundAmount, VOLUNTARY_CANCELLATION_RETAINED_FEE_PCT);
        TaxCalculation tax = TaxCalculation.calculate(zero, zero, zero, BigDecimal.ZERO, refundAmount);
        FeeAccrual accrual = FeeAccrual.accrue(orderId, zero, zero, retainedCancellationFee, tax, sourceEventId, now, PrefixedIds.newCommandId(), causationId, canonicalCorrelationId(correlationId));
        feeAccruals.save(accrual);
        publish(accrual.domainEvents());
        return accrual;
    }

    private Money refundAdjustmentsForOrder(String orderId, Currency currency) {
        List<FeeAccrual> orderFees = feeAccruals.findByOrderId(orderId);
        return orderFees.stream()
            .map(FeeAccrual::taxCalculation)
            .map(TaxCalculation::taxReversal)
            .filter(amount -> amount.currency().equals(currency))
            .reduce(Money.zero(currency), Money::plus);
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

    private static String reconciliationKey(String orderId, String paymentIntentId) {
        String normalizedOrderId = orderId == null ? "" : orderId;
        String normalizedPaymentIntentId = paymentIntentId == null ? "" : paymentIntentId;
        return normalizedOrderId.isBlank() ? normalizedPaymentIntentId : normalizedOrderId;
    }

    private record PlatformReconciliationFact(String orderId, String paymentIntentId, Money amount, List<String> sourceEventIds) {
        private PlatformReconciliationFact merge(PlatformReconciliationFact other) {
            List<String> mergedEvents = new ArrayList<>(sourceEventIds);
            mergedEvents.addAll(other.sourceEventIds);
            return new PlatformReconciliationFact(orderId, paymentIntentId.isBlank() ? other.paymentIntentId : paymentIntentId, amount.plus(other.amount), mergedEvents);
        }
    }

    private record ChannelReconciliationFact(String orderId, String paymentIntentId, Money amount, List<String> sourceEventIds) {
        private ChannelReconciliationFact merge(ChannelReconciliationFact other) {
            List<String> mergedEvents = new ArrayList<>(sourceEventIds);
            mergedEvents.addAll(other.sourceEventIds);
            return new ChannelReconciliationFact(orderId, paymentIntentId.isBlank() ? other.paymentIntentId : paymentIntentId, amount.plus(other.amount), mergedEvents);
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
