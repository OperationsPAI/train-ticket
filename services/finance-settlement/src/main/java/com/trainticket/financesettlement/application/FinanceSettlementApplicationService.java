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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FinanceSettlementApplicationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinanceSettlementApplicationService.class);
    private final RevenueRecognitionRepository revenueRecognitions;
    private final ReconciliationCaseRepository reconciliationCases;
    private final InvoiceRepository invoices;
    private final FinanceSettlementProjectionRepository projections;
    private final ReconciliationBatchRepository reconciliationBatches;
    private static final BigDecimal PLATFORM_SERVICE_FEE_PCT = new BigDecimal("0.02");
    private static final BigDecimal SUPPLIER_SERVICE_FEE_PCT = new BigDecimal("0.01");
    private static final BigDecimal VOLUNTARY_CANCELLATION_RETAINED_FEE_PCT = new BigDecimal("0.10");
    // 2,000 pages of 500 lines is a million lines: past any statement this
    // environment has measured (the largest is 342,648), and a bound rather
    // than a target, so a statement that keeps growing cannot hold the
    // scheduler's single thread forever.
    private static final int MAX_STATEMENT_LINE_PAGES = 2_000;
    /**
     * A bound rather than a target: 1000 pages of 1000 is a million refunds for
     * one day, four times the largest day measured here. It exists so one bad
     * date cannot hold the scheduler's thread forever -- and if it is ever
     * reached, the run says so, because a silently truncated platform side
     * reports every refund it did not read as one the platform never settled.
     * Measured at the first cut of this code, when the bound was 100 pages: the
     * day's last 30236 refunds were read as channel-only.
     */
    private static final int MAX_PLATFORM_REFUND_PAGES = 1_000;

    private final SupplierSettlementRepository supplierSettlements;
    private final FeeAccrualRepository feeAccruals;
    private final EventPublisher eventPublisher;
    private final DomainEventEnvelopeMapper envelopeMapper;
    private final Clock clock;
    private ChannelStatementLineSource channelStatementLines;
    private PaymentIntentOrderReferenceRepository paymentIntentOrderReferences;
    private PlatformRefundSource platformRefundsSource;

    /**
     * Where the channel's own statement lines come from, when this deployment
     * has a source for them. Without one the reconciliation keeps its
     * statement-level fallback.
     */
    public FinanceSettlementApplicationService withChannelStatementLines(ChannelStatementLineSource source) {
        this.channelStatementLines = source;
        return this;
    }

    /**
     * Resolves the order a channel line settles from the payment intent, which
     * is the reference both sides carry.
     */
    public FinanceSettlementApplicationService withPaymentIntentOrderReferences(PaymentIntentOrderReferenceRepository references) {
        this.paymentIntentOrderReferences = references;
        return this;
    }

    /**
     * Where the platform's own settled refunds come from, when this deployment
     * has a source for them. Without one the reconciliation checks the money in
     * and leaves the refund side to the channel's own record.
     */
    public FinanceSettlementApplicationService withPlatformRefunds(PlatformRefundSource source) {
        this.platformRefundsSource = source;
        return this;
    }

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

    public List<String> findSupplierIdsForPeriod(LocalDate startDate, LocalDate endDate) {
        Objects.requireNonNull(startDate, "startDate is required");
        Objects.requireNonNull(endDate, "endDate is required");
        if (endDate.isBefore(startDate)) throw new ValidationException("endDate must not be before startDate");
        return revenueRecognitions.findSupplierIdsForPeriod(startDate, endDate);
    }

    List<RevenueRecognition> findRevenueRecognitionsByOrderId(String orderId) {
        return revenueRecognitions.findByOrderId(orderId);
    }

    public void lockOrder(String orderId) { revenueRecognitions.lockOrder(orderId); }

    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> revenueBasis(String orderId) {
        requireText(orderId,"orderId");
        revenueRecognitions.lockOrder(orderId);
        var records=revenueRecognitions.findByOrderId(orderId);
        if(records.isEmpty())throw new ResourceNotFoundException("order revenue has not been recognized");
        Money net=records.stream().map(RevenueRecognition::netAmount).reduce(Money::plus).orElseThrow();
        var items=records.stream().map(record->{
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("revenueRecognitionId",record.revenueRecognitionId());
            row.put("amount",DomainEventEnvelopeMapper.moneyPayload(record.amount()));
            row.put("reversedAmount",DomainEventEnvelopeMapper.moneyPayload(record.reversedAmount()));
            row.put("reversals",record.reversalAmounts().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,entry->DomainEventEnvelopeMapper.moneyPayload(entry.getValue()))));
            return row;
        }).toList();
        return Map.of("orderId",orderId,"totalAmount",DomainEventEnvelopeMapper.moneyPayload(net),"items",items);
    }

    public void recordChangeFee(String orderId,Money fee,String caseId,String correlationId) {
        var existing=feeAccruals.findByOrderId(orderId).stream().filter(item->caseId.equals(item.sourceEventId())).findFirst();
        if(existing.isPresent()) {
            if(!existing.get().platformServiceFee().equals(fee))throw new DomainRuleViolation("recorded change fee differs from approved fee");
            return;
        }
        if(fee.isZero())return;
        Money zero=Money.zero(fee.currency());
        var tax=TaxCalculation.calculate(fee,zero,zero,BigDecimal.ZERO,zero);
        var accrual=FeeAccrual.accrue(orderId,fee,zero,zero,tax,caseId,clock.instant(),PrefixedIds.newCommandId(),PrefixedIds.newCommandId(),canonicalCorrelationId(correlationId));
        feeAccruals.save(accrual);
        publish(accrual.domainEvents());
    }

    public void recordPostSalesFee(String orderId,Money fee,String caseId,String correlationId) {
        var existing=feeAccruals.findByOrderId(orderId).stream().filter(item->caseId.equals(item.sourceEventId())).findFirst();
        if(existing.isPresent()) {
            if(!existing.get().retainedCancellationFee().equals(fee))throw new DomainRuleViolation("recorded refund fee differs from approved penalty");
            return;
        }
        if(fee.isZero())return;
        Money zero=Money.zero(fee.currency());
        var tax=TaxCalculation.calculate(zero,zero,zero,BigDecimal.ZERO,zero);
        var accrual=FeeAccrual.accrue(orderId,zero,zero,fee,tax,caseId,clock.instant(),PrefixedIds.newCommandId(),PrefixedIds.newCommandId(),canonicalCorrelationId(correlationId));
        feeAccruals.save(accrual);
        publish(accrual.domainEvents());
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

    public Page<AncillaryFinancialFact> listAncillaryFinancialFacts(String journeyOrderId, int limit, int offset) {
        if (limit < 1 || limit > 100) {
            throw new ValidationException("limit must be between 1 and 100");
        }
        if (offset < 0) {
            throw new ValidationException("offset must not be negative");
        }
        List<AncillaryFinancialFact> items = projections.findAncillaryFinancialFacts(journeyOrderId, limit, offset);
        return new Page<>(items, projections.countAncillaryFinancialFacts(journeyOrderId), limit, offset);
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

    @org.springframework.transaction.annotation.Transactional
    public ReconciliationBatch runDailyReconciliation(LocalDate settlementDate, String correlationId) {
        LocalDate date = settlementDate == null ? LocalDate.now(clock).minusDays(1) : settlementDate;
        return reconciliationBatches.findBySettlementDate(date).orElseGet(() -> {
            ingestChannelStatementLines(date, correlationId);
            Currency[] currency = {Currency.getInstance("CNY")};
            Map<String, PlatformReconciliationFact> captures = new LinkedHashMap<>();
            for (FinanceSettlementEventHandler.PaymentCaptureFact capture : projections.findCapturesForSettlementDate(date)) {
                String key = reconciliationKey(capture.orderId(), capture.paymentIntentId());
                captures.merge(key, new PlatformReconciliationFact(capture.orderId(), capture.paymentIntentId(), capture.amount(), List.of(capture.sourceEventId())), PlatformReconciliationFact::merge);
                currency[0] = capture.amount().currency();
            }
            Map<String, ChannelReconciliationFact> channelLines = new LinkedHashMap<>();
            List<ChannelStatementLineProjection> channelLineRows = projections.findChannelStatementLinesForSettlementDate(date);
            for (ChannelStatementLineProjection line : channelLineRows) {
                // Only the channel's payments answer the platform's captures. A
                // refund line is the channel's answer to a platform refund, and
                // entering it here would report every refund as money the
                // platform cannot explain.
                if (!line.isPayment()) continue;
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
            entries.addAll(reconcileRefunds(date, channelLineRows, currency));
            ReconciliationBatch batch = ReconciliationBatch.complete(date, date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), currency[0], entries,
                clock.instant(), PrefixedIds.newCommandId(), PrefixedIds.newCommandId(), canonicalCorrelationId(correlationId));
            reconciliationBatches.save(batch);
            publish(batch.domainEvents());
            return batch;
        });
    }

    @org.springframework.transaction.annotation.Transactional
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

    /**
     * Compares the channel's refund lines with the refunds the platform settled.
     *
     * <p>A statement's refund lines are money the channel paid back, and the
     * platform's own record of paying it back is payment's settlement of the same
     * refund id -- the one reference both sides carry. Without this the day's
     * gross refund total was recorded by the channel and never checked against
     * anything, because the capture comparison reads payment lines only.
     */
    private List<ReconciliationEntry> reconcileRefunds(LocalDate date, List<ChannelStatementLineProjection> channelLineRows, Currency[] currency) {
        if (platformRefundsSource == null) {
            // No platform side to compare with: the statement's own gross refund
            // total stays the channel's record rather than becoming one
            // unexplained entry per refund line.
            return List.of();
        }
        if (channelLineRows.isEmpty()) {
            // No statement for the date means no channel side to compare with, so
            // the platform side is not pulled either: the read is what a
            // settlement needs, and a date nothing was settled for costs nothing.
            return List.of();
        }
        Map<String, ChannelReconciliationFact> channelRefunds = new LinkedHashMap<>();
        for (ChannelStatementLineProjection line : channelLineRows) {
            if (!line.isRefund() || line.refundId().isBlank()) continue;
            channelRefunds.merge(line.refundId(),
                new ChannelReconciliationFact(line.orderId(), line.paymentIntentId(), line.actualAmount(), List.of(line.sourceEventId())),
                ChannelReconciliationFact::merge);
            currency[0] = line.actualAmount().currency();
        }
        Map<String, PlatformReconciliationFact> settledRefunds = new LinkedHashMap<>();
        if (platformRefundsSource != null) {
            String cursor = null;
            int pages = 0;
            for (int page = 0; page < MAX_PLATFORM_REFUND_PAGES; page++) {
                PlatformRefundSource.Page current = platformRefundsSource.page(date, cursor, PlatformRefundSource.MAX_PAGE_SIZE);
                pages++;
                for (PlatformRefundSource.Refund refund : current.refunds()) {
                    if (refund.refundId().isBlank()) continue;
                    String reference = refund.businessReference().isBlank() ? refund.sourceCaseRef() : refund.businessReference();
                    settledRefunds.merge(refund.refundId(),
                        new PlatformReconciliationFact(reference, refund.paymentIntentId(), refund.amount(), List.of(refund.refundId())),
                        PlatformReconciliationFact::merge);
                    currency[0] = refund.amount().currency();
                }
                if (current.nextCursor() == null) break;
                cursor = current.nextCursor();
            }
            if (cursor != null) {
                LOGGER.warn(
                    "finance settlement read {} pages of settled refunds for {} and stopped at the page bound; the platform side of the refund comparison is incomplete",
                    pages, date);
            }
        }
        if (channelRefunds.isEmpty() && settledRefunds.isEmpty()) {
            return List.of();
        }
        List<ReconciliationEntry> entries = new ArrayList<>();
        for (var item : settledRefunds.entrySet()) {
            ChannelReconciliationFact channel = channelRefunds.remove(item.getKey());
            Money channelAmount = channel == null ? Money.zero(item.getValue().amount().currency()) : channel.amount();
            List<String> sourceEventIds = new ArrayList<>(item.getValue().sourceEventIds());
            if (channel != null) {
                sourceEventIds.addAll(channel.sourceEventIds());
            }
            entries.add(ReconciliationEntry.compare("re-refund-" + item.getKey(), item.getValue().orderId(),
                item.getValue().paymentIntentId(), item.getValue().amount(), channelAmount, true, channel != null, sourceEventIds));
        }
        for (var item : channelRefunds.entrySet()) {
            entries.add(ReconciliationEntry.compare("re-refund-" + item.getKey(), item.getValue().orderId(),
                item.getValue().paymentIntentId(), Money.zero(item.getValue().amount().currency()), item.getValue().amount(),
                false, true, item.getValue().sourceEventIds()));
        }
        return entries;
    }

    /**
     * Records the channel's own lines for a settlement date, so the daily
     * reconciliation compares captures with lines rather than with one
     * statement-level total.
     *
     * The channel side is read once per statement: a statement whose lines are
     * already recorded is skipped, and the write is an upsert keyed by the
     * statement line id, so a re-read of a half-written statement is a no-op
     * rather than a duplicate.
     *
     * @return how many lines this call recorded
     */
    public int ingestChannelStatementLines(LocalDate settlementDate, String correlationId) {
        if (channelStatementLines == null) return 0;
        int recorded = 0;
        for (ChannelStatementProjection statement : projections.findChannelStatementsForSettlementDate(settlementDate)) {
            if (projections.hasChannelStatementLines(statement.channelStatementId())) continue;
            int forStatement = ingestStatementLines(statement);
            recorded += forStatement;
            if (forStatement > 0) {
                System.out.printf(
                    "finance settlement: read %d channel statement lines for %s %s (%s)%n",
                    forStatement, statement.channel(), statement.statementDate(), statement.channelStatementId());
            }
        }
        return recorded;
    }

    private int ingestStatementLines(ChannelStatementProjection statement) {
        long after = 0;
        int recorded = 0;
        for (int page = 0; page < MAX_STATEMENT_LINE_PAGES; page++) {
            ChannelStatementLineSource.Page current = channelStatementLines.page(
                statement.channelStatementId(), after, ChannelStatementLineSource.MAX_PAGE_SIZE);
            if (current.lines().isEmpty()) break;
            // One query per page rather than one per line: the statement is
            // hundreds of thousands of lines and this lookup is on the path of
            // every one of them.
            java.util.Map<String, String> orderReferences = orderReferencesFor(current.lines());
            List<ChannelStatementLineProjection> batch = new ArrayList<>(current.lines().size());
            for (ChannelStatementLineSource.Line line : current.lines()) {
                batch.add(new ChannelStatementLineProjection(
                    line.statementLineId(),
                    statement.channelStatementId(),
                    statement.statementDate(),
                    orderReferences.getOrDefault(line.paymentIntentId(), ""),
                    line.paymentIntentId(),
                    line.channelOrderId(),
                    line.actualAmount(),
                    // The line itself is the evidence: this side of the money was
                    // read from the channel's statement, not published as an event.
                    line.statementLineId(),
                    line.lineType().isBlank() ? ChannelStatementLineProjection.PAYMENT : line.lineType(),
                    line.refundId()
                ));
            }
            projections.saveChannelStatementLines(batch);
            recorded += batch.size();
            if (current.nextCursor() == null) break;
            after = current.nextCursor();
        }
        return recorded;
    }

    /** One lookup per page for the intents the page's lines name. */
    private java.util.Map<String, String> orderReferencesFor(List<ChannelStatementLineSource.Line> lines) {
        if (paymentIntentOrderReferences == null) return java.util.Map.of();
        List<String> intents = lines.stream()
            .map(ChannelStatementLineSource.Line::paymentIntentId)
            .filter(paymentIntentId -> !paymentIntentId.isBlank())
            .distinct()
            .toList();
        return intents.isEmpty() ? java.util.Map.of() : paymentIntentOrderReferences.findOrderReferences(intents);
    }

    private static String reconciliationKey(String orderId, String paymentIntentId) {
        String normalizedPaymentIntentId = paymentIntentId == null ? "" : paymentIntentId;
        // The payment intent is the reference both sides carry: the platform's
        // capture fact names the intent, and so does the channel's line. The
        // business reference is not shared -- payment-channel knows an intent
        // and a channel order, while finance attributes a capture to the order
        // or to the ancillary item the money was for -- so keying on it
        // collapsed the several payments one order can carry into a single
        // entry, and every one of them read as a channel amount the platform
        // could not explain. Measured on 2026-09-26 before this: 56,884 of
        // 431,530 entries were amount mismatches, and every one was an order
        // whose two payments the channel had named separately.
        if (!normalizedPaymentIntentId.isBlank()) return normalizedPaymentIntentId;
        return orderId == null ? "" : orderId;
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
