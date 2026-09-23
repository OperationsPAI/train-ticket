package com.trainticket.financesettlement.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.trainticket.financesettlement.domain.ConsumedEventLog;
import com.trainticket.financesettlement.domain.DomainRuleViolation;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.ReconciliationCase;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

public class FinanceSettlementEventHandler implements EventSubscriber.EventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinanceSettlementEventHandler.class);
    private static final int ZERO_REFUND_WARNING_SAMPLE_RATE = 100;
    private static final AtomicInteger ZERO_REFUND_WARNING_SEQUENCE = new AtomicInteger();
    private final ConsumedEventLogRepository consumedEvents;
    private final PaymentIntentOrderReferenceRepository paymentIntentOrderReferences;
    private final SegmentBookingOrderReferenceRepository segmentBookingOrderReferences;
    private final FinanceSettlementProjectionRepository projections;
    private final Clock clock;
    private final FinanceSettlementApplicationService service;
    private com.trainticket.financesettlement.adapters.http.ChangePaymentFactsClient changePayments;

    public FinanceSettlementEventHandler withChangePayments(com.trainticket.financesettlement.adapters.http.ChangePaymentFactsClient client) {
        this.changePayments=java.util.Objects.requireNonNull(client);
        return this;
    }

    public FinanceSettlementEventHandler(ConsumedEventLogRepository consumedEvents, Clock clock) {
        this(
            consumedEvents,
            new InMemoryPaymentIntentOrderReferenceRepository(),
            new InMemorySegmentBookingOrderReferenceRepository(),
            new InMemoryFinanceSettlementProjectionRepository(),
            clock,
            null
        );
    }

    public FinanceSettlementEventHandler(
        ConsumedEventLogRepository consumedEvents,
        PaymentIntentOrderReferenceRepository paymentIntentOrderReferences,
        Clock clock,
        FinanceSettlementApplicationService service
    ) {
        this(
            consumedEvents,
            paymentIntentOrderReferences,
            new InMemorySegmentBookingOrderReferenceRepository(),
            new InMemoryFinanceSettlementProjectionRepository(),
            clock,
            service
        );
    }

    public FinanceSettlementEventHandler(
        ConsumedEventLogRepository consumedEvents,
        PaymentIntentOrderReferenceRepository paymentIntentOrderReferences,
        SegmentBookingOrderReferenceRepository segmentBookingOrderReferences,
        Clock clock,
        FinanceSettlementApplicationService service
    ) {
        this(
            consumedEvents,
            paymentIntentOrderReferences,
            segmentBookingOrderReferences,
            new InMemoryFinanceSettlementProjectionRepository(),
            clock,
            service
        );
    }

    public FinanceSettlementEventHandler(
        ConsumedEventLogRepository consumedEvents,
        PaymentIntentOrderReferenceRepository paymentIntentOrderReferences,
        SegmentBookingOrderReferenceRepository segmentBookingOrderReferences,
        FinanceSettlementProjectionRepository projections,
        Clock clock,
        FinanceSettlementApplicationService service
    ) {
        this.consumedEvents = consumedEvents;
        this.paymentIntentOrderReferences = paymentIntentOrderReferences;
        this.segmentBookingOrderReferences = segmentBookingOrderReferences;
        this.projections = projections;
        this.clock = clock;
        this.service = service;
    }

    @Override
    @Transactional
    public HandlerResult handle(EventEnvelope envelope) {
        validateEnvelopeIds(envelope);
        ConsumedEventLog log = ConsumedEventLog.record(envelope.eventId(), envelope.producer(), envelope.eventType(), clock.instant());
        boolean preRecorded = consumedEvents.guardsTransactionally();
        if (preRecorded && !consumedEvents.recordIfNew(log)) {
            return HandlerResult.SUCCESS;
        }
        if (!preRecorded && consumedEvents.existsByEventId(envelope.eventId())) {
            return HandlerResult.SUCCESS;
        }
        try {
            dispatch(envelope);
            if (!preRecorded) {
                consumedEvents.save(log);
            }
            return HandlerResult.SUCCESS;
        } catch (PublishFailedException | OutOfOrderEventException ex) {
            LOGGER.warn("service=finance-settlement eventId={} eventType={} exceptionClass={} exceptionMessage={} transient handling failure",
                envelope.eventId(), envelope.eventType(), ex.getClass().getName(), ex.getMessage(), ex);
            rollbackCurrentTransactionIfActive();
            return HandlerResult.TRANSIENT_FAILURE;
        } catch (DomainRuleViolation | IllegalArgumentException ex) {
            LOGGER.warn("service=finance-settlement eventId={} eventType={} exceptionClass={} exceptionMessage={} fatal handling failure",
                envelope.eventId(), envelope.eventType(), ex.getClass().getName(), ex.getMessage(), ex);
            rollbackCurrentTransactionIfActive();
            return HandlerResult.FATAL_FAILURE;
        }
    }

    private static void validateEnvelopeIds(EventEnvelope envelope) {
        PrefixedIds.requireEventId(envelope.eventId());
        PrefixedIds.requireCorrelationId(envelope.correlationId());
        if (envelope.causationId() != null) {
            PrefixedIds.requireCausationId(envelope.causationId());
        }
    }

    public static boolean handlesEvent(String eventType) {
        return switch (eventType) {
            case "PaymentIntentCreated", "JourneyOrderCreated", "SegmentReservationRequested", "PaymentCaptured",
                 "ProviderReservationConfirmed", "SegmentBookingCancelled", "PostSalesApproved", "PostSalesApplied",
                 "BenefitIssued", "BenefitRedeemed", "BenefitRedemptionReversed", "BenefitRevoked", "BenefitExpired",
                 "AncillaryOrderItemFulfilled", "AncillaryOrderItemCancelled", "AncillaryOrderItemRefundPending",
                 "AncillaryOrderItemRefunded", "AncillaryFulfillmentFactRecorded", "ChannelStatementGenerated",
                 "ChannelStatementFrozen", "ChannelStatementLineMatched", "ReconciliationDiscrepancyOpened",
                 "ReconciliationDiscrepancyResolved" -> true;
            default -> false;
        };
    }

    private static void rollbackCurrentTransactionIfActive() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (RuntimeException ignored) {
            // Unit tests may run without a Spring-managed transaction.
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatch(EventEnvelope envelope) {
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        switch (envelope.eventType()) {
            case "PaymentIntentCreated" -> rememberPaymentIntentOrderReference(envelope,payload);
            case "JourneyOrderCreated" -> rememberOrderSupplierReference(payload);
            case "SegmentReservationRequested" -> rememberSegmentBookingOrderReference(payload);
            case "PaymentCaptured" -> {
                LOGGER.info("service=finance-settlement PaymentCaptured eventId={} serviceWired={} projections={} serviceRepos={}",
                    envelope.eventId(), service != null, projections.getClass().getSimpleName(),
                    service == null ? "-" : service.describeWiring());
                if (service != null) recognizeCapturedPayment(envelope, payload);
            }
            case "ProviderReservationConfirmed", "SegmentBookingCancelled" -> {
                if (service != null) reconcileOperationalFact(envelope, payload);
            }
            case "PostSalesApproved" -> rememberApprovedRefund(payload);
            case "PostSalesApplied" -> {
                if (service != null) applyPostSales(envelope, payload);
            }
            case "BenefitIssued", "BenefitRedeemed", "BenefitRedemptionReversed", "BenefitRevoked", "BenefitExpired" -> recordBenefitCostEntry(envelope, payload);
            case "AncillaryOrderItemFulfilled", "AncillaryOrderItemCancelled", "AncillaryOrderItemRefundPending", "AncillaryOrderItemRefunded", "AncillaryFulfillmentFactRecorded" -> handleAncillaryFinancialEvent(envelope, payload);
            case "ChannelStatementGenerated", "ChannelStatementFrozen" -> recordChannelStatement(envelope, payload);
            case "ChannelStatementLineMatched" -> recordChannelStatementLine(envelope, payload);
            case "ReconciliationDiscrepancyOpened" -> openChannelDiscrepancy(envelope, payload);
            case "ReconciliationDiscrepancyResolved" -> {
                // Finance owns final accounting case resolution; channel-side resolution is recorded by consumed-event dedup.
            }
            default -> {
                // Streams contain event types that do not affect finance settlement.
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void rememberOrderSupplierReference(Map<String, Object> payload) {
        String orderId = text(payload, "orderId");
        Object supplierValue = payload.get("supplierId");
        if (supplierValue instanceof String supplierId && !supplierId.isBlank()) {
            segmentBookingOrderReferences.save("supplier:" + orderId, supplierId);
            return;
        }
        Object refsValue = payload.get("supplierRefs");
        if (refsValue instanceof List<?> refs) {
            refs.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(ref -> !ref.isBlank())
                .findFirst()
                .ifPresent(ref -> segmentBookingOrderReferences.save("supplier:" + orderId, ref));
            return;
        }
        Object monetarySummary = payload.get("monetarySummary");
        if (monetarySummary instanceof Map<?, ?> rawSummary && rawSummary.get("supplierId") instanceof String supplierId && !supplierId.isBlank()) {
            segmentBookingOrderReferences.save("supplier:" + orderId, supplierId);
        }
    }

    private void recordChannelStatement(EventEnvelope envelope, Map<String, Object> payload) {
        ChannelStatementProjection existing = projections.findChannelStatement(text(payload, "channelStatementId")).orElse(null);
        projections.saveChannelStatement(new ChannelStatementProjection(
            text(payload, "channelStatementId"),
            text(payload, "channel"),
            text(payload, "statementDate"),
            text(payload, "currency"),
            text(payload, "seedVersion"),
            number(payload, "lineCount").intValue(),
            money(payload.get("grossPaymentAmount"), "grossPaymentAmount"),
            money(payload.get("grossRefundAmount"), "grossRefundAmount"),
            money(payload.get("feeAmount"), "feeAmount"),
            text(payload, "statementHash"),
            text(payload, "status"),
            existing == null ? Instant.parse(text(payload, "generatedAt")) : existing.generatedAt(),
            optionalText(payload, "frozenAt").map(Instant::parse).orElse(existing == null ? null : existing.frozenAt()),
            envelope.eventId()
        ));
    }

    private void recordChannelStatementLine(EventEnvelope envelope, Map<String, Object> payload) {
        String channelStatementId = text(payload, "channelStatementId");
        ChannelStatementProjection statement = projections.findChannelStatement(channelStatementId).orElse(null);
        String paymentIntentId = optionalText(payload, "paymentIntentId").orElse("");
        String orderId = optionalText(payload, "orderId")
            .or(() -> paymentIntentId.isBlank() ? Optional.empty() : paymentIntentOrderReferences.findOrderReference(paymentIntentId))
            .orElse(optionalText(payload, "channelOrderId").orElse(optionalText(payload, "channelRefundId").orElse("")));
        projections.saveChannelStatementLine(new ChannelStatementLineProjection(
            text(payload, "statementLineId"),
            channelStatementId,
            statement == null ? optionalText(payload, "statementDate").orElse("") : statement.statementDate(),
            orderId,
            paymentIntentId,
            optionalText(payload, "channelOrderId").orElse(""),
            money(payload.get("actualAmount"), "actualAmount"),
            envelope.eventId()
        ));
    }

    private void openChannelDiscrepancy(EventEnvelope envelope, Map<String, Object> payload) {
        if (service == null) {
            return;
        }
        ReconciliationCase open = ReconciliationCase.open(
            optionalText(payload, "channelOrderId").orElse(optionalText(payload, "channelRefundId").orElse("")),
            optionalText(payload, "paymentIntentId").orElse(""),
            mapChannelDifference(text(payload, "differenceType")),
            money(payload.get("expectedAmount"), "expectedAmount"),
            money(payload.get("actualAmount"), "actualAmount"),
            "Payment Channel discrepancy " + text(payload, "discrepancyId") + " on statement " + text(payload, "channelStatementId"),
            clock.instant(),
            causationIdOrEventId(envelope),
            envelope.correlationId()
        );
        service.saveAndPublish(open);
    }

    private static String mapChannelDifference(String differenceType) {
        return switch (differenceType) {
            case "MISSING_IN_CHANNEL" -> "missing-in-channel";
            case "MISSING_IN_PLATFORM" -> "missing-in-platform";
            case "AMOUNT_MISMATCH" -> "amount-mismatch";
            case "CURRENCY_MISMATCH" -> "currency-mismatch";
            case "DUPLICATE" -> "duplicate";
            case "REFUND_LAG" -> "refund-lag";
            case "LATE_PAYMENT" -> "late-payment";
            case "STATUS_MISMATCH" -> "status-mismatch";
            default -> throw new IllegalArgumentException("unsupported channel differenceType " + differenceType);
        };
    }

    private void recordBenefitCostEntry(EventEnvelope envelope, Map<String, Object> payload) {
        String benefitId = text(payload, "benefitId");
        BenefitCostEntry previousEntry = projections.findLatestBenefitCostEntryForBenefit(benefitId).orElse(null);
        projections.saveBenefitCostEntry(new BenefitCostEntry(
            envelope.eventId(),
            benefitId,
            text(payload, "accountId"),
            optionalText(payload, "issuanceSource").orElse(previousEntry == null ? "UNKNOWN" : previousEntry.issuanceSource()),
            optionalText(payload, "caseId").orElse(previousEntry == null ? null : previousEntry.caseId()),
            benefitCostAmount(envelope.eventType(), payload),
            normalizedBenefitCostEventType(envelope.eventType()),
            benefitOccurredAt(envelope, payload)
        ));
    }

    private void handleAncillaryFinancialEvent(EventEnvelope envelope, Map<String, Object> payload) {
        AncillaryFinancialFact fact = ancillaryFinancialFact(envelope, payload);
        projections.saveAncillaryFinancialFact(fact);
        if (service == null) {
            return;
        }
        switch (envelope.eventType()) {
            case "AncillaryOrderItemFulfilled" -> recognizeAncillaryRevenue(envelope, fact);
            case "AncillaryOrderItemCancelled" -> accrueAncillaryRetention(envelope, fact);
            case "AncillaryOrderItemRefundPending" -> recordAncillaryRefundPending(envelope, fact, payload);
            case "AncillaryOrderItemRefunded" -> applyAncillaryRefund(envelope, fact);
            case "AncillaryFulfillmentFactRecorded" -> reconcileAncillaryFulfillmentFact(envelope, fact, payload);
            default -> { }
        }
    }

    private AncillaryFinancialFact ancillaryFinancialFact(EventEnvelope envelope, Map<String, Object> payload) {
        Money payableAmount = optionalMoney(payload, "payableAmount").orElse(null);
        Money refundableAmount = optionalMoney(payload, "refundableAmount").orElse(null);
        Money refundedAmount = optionalMoney(payload, "refundedAmount").orElse(null);
        Money retainedAmount = retainedAncillaryAmount(envelope.eventType(), payableAmount, refundableAmount, refundedAmount);
        return new AncillaryFinancialFact(
            envelope.eventId(),
            envelope.eventType(),
            ancillaryFactKind(envelope.eventType()),
            text(payload, "ancillaryOrderItemId"),
            text(payload, "journeyOrderId"),
            ancillaryServiceType(payload),
            supplierReferenceForAncillary(payload),
            payableAmount,
            refundableAmount,
            refundedAmount,
            retainedAmount,
            ancillaryOccurredAt(envelope, payload)
        );
    }

    private void recognizeAncillaryRevenue(EventEnvelope envelope, AncillaryFinancialFact fact) {
        Money payableAmount = fact.payableAmount();
        if (payableAmount == null || payableAmount.isZero()) {
            return;
        }
        if (hasRevenueRecognitionForSourceEvent(fact.eventId())) {
            return;
        }
        RevenueRecognition recognition = RevenueRecognition.recognize(
            fact.journeyOrderId(),
            fact.ancillaryOrderItemId(),
            "ancillary",
            payableAmount,
            "ancillary-fulfillment-v1",
            fact.eventId(),
            fact.occurredAt(),
            clock.instant(),
            causationIdOrEventId(envelope),
            envelope.correlationId()
        );
        service.saveAndPublish(recognition);
        service.accrueFeesForPaymentCapture(fact.journeyOrderId(), payableAmount, Money.zero(payableAmount.currency()), fact.eventId(), clock.instant(), causationIdOrEventId(envelope), envelope.correlationId());
    }

    private void accrueAncillaryRetention(EventEnvelope envelope, AncillaryFinancialFact fact) {
        Money retainedAmount = fact.retainedAmount();
        if (retainedAmount == null || retainedAmount.isZero()) {
            return;
        }
        service.accrueFeesForRefund(fact.journeyOrderId(), retainedAmount, retainedAmount.currency(), fact.eventId(), clock.instant(), causationIdOrEventId(envelope), envelope.correlationId());
    }

    private void recordAncillaryRefundPending(EventEnvelope envelope, AncillaryFinancialFact fact, Map<String, Object> payload) {
        Money refundableAmount = fact.refundableAmount();
        if (refundableAmount == null || refundableAmount.isZero()) {
            return;
        }
        optionalText(payload, "postSalesCaseId").ifPresent(caseId -> projections.saveApprovedRefund(caseId, refundableAmount));
        projections.saveApprovedRefund(fact.ancillaryOrderItemId(), refundableAmount);
        projections.saveApprovedRefund(fact.journeyOrderId() + ":" + fact.ancillaryOrderItemId(), refundableAmount);
        if ("MANUAL_REVIEW".equals(optionalText(payload, "recommendation").orElse(""))) {
            ReconciliationCase open = ReconciliationCase.open(
                fact.journeyOrderId(),
                "",
                "refund-lag",
                refundableAmount.negate(),
                Money.zero(refundableAmount.currency()),
                "Ancillary refund pending requires manual finance review for item " + fact.ancillaryOrderItemId(),
                clock.instant(),
                causationIdOrEventId(envelope),
                envelope.correlationId()
            );
            service.saveAndPublish(open);
        }
    }

    private void applyAncillaryRefund(EventEnvelope envelope, AncillaryFinancialFact fact) {
        Money refundedAmount = fact.refundedAmount();
        if (refundedAmount == null || refundedAmount.isZero()) {
            return;
        }
        Optional<RevenueRecognition> matchingRecognition = serviceRevenue(fact.journeyOrderId()).stream()
            .filter(recognition -> fact.ancillaryOrderItemId().equals(recognition.orderItemId()))
            .filter(recognition -> "ancillary".equals(recognition.componentCode()))
            .filter(recognition -> !recognition.reversed())
            .filter(recognition -> sameCurrency(recognition.amount(), refundedAmount))
            .filter(recognition -> recognition.amount().compareTo(refundedAmount) >= 0)
            .findFirst();
        if (matchingRecognition.isEmpty()) {
            PaymentCaptureFact ancillaryCapture = projections.findCapture(fact.ancillaryOrderItemId()).orElse(null);
            if (ancillaryCapture != null && sameCurrency(ancillaryCapture.amount(),refundedAmount)
                && ancillaryCapture.amount().compareTo(refundedAmount)>=0) return;
            ReconciliationCase open = ReconciliationCase.open(
                fact.journeyOrderId(),
                "",
                "refund-lag",
                refundedAmount.negate(),
                Money.zero(refundedAmount.currency()),
                "Ancillary refund could not be matched to recognized revenue for item " + fact.ancillaryOrderItemId(),
                clock.instant(),
                causationIdOrEventId(envelope),
                envelope.correlationId()
            );
            service.saveAndPublish(open);
            return;
        }
        RevenueRecognition originalRecognition = matchingRecognition.get();
        int firstUnpublishedEventIndex = originalRecognition.domainEvents().size();
        originalRecognition.reverse(
            "ancillary refund recorded",
            fact.eventId(),
            refundedAmount,
            clock.instant(),
            causationIdOrEventId(envelope),
            causationIdOrEventId(envelope),
            envelope.correlationId()
        );
        service.saveAndPublish(originalRecognition, firstUnpublishedEventIndex);
        service.accrueFeesForRefund(fact.journeyOrderId(), refundedAmount, refundedAmount.currency(), fact.eventId(), clock.instant(), causationIdOrEventId(envelope), envelope.correlationId());
    }

    private void reconcileAncillaryFulfillmentFact(EventEnvelope envelope, AncillaryFinancialFact fact, Map<String, Object> payload) {
        if (!"FULFILLED".equals(optionalText(payload, "status").orElse(""))) {
            return;
        }
        List<RevenueRecognition> recognitions = serviceRevenue(fact.journeyOrderId()).stream()
            .filter(recognition -> fact.ancillaryOrderItemId().equals(recognition.orderItemId()))
            .filter(recognition -> "ancillary".equals(recognition.componentCode()))
            .toList();
        Money actual = recognitions.stream()
            .map(RevenueRecognition::netAmount)
            .filter(amount -> fact.payableAmount() == null || sameCurrency(amount, fact.payableAmount()))
            .reduce(fact.payableAmount() == null ? Money.zero(Currency.getInstance("CNY")) : Money.zero(fact.payableAmount().currency()), Money::plus);
        Money expected = fact.payableAmount() == null ? actual : fact.payableAmount();
        if (!recognitions.isEmpty() && sameMoney(expected, actual)) {
            service.publishReconciliationCompleted(
                fact.journeyOrderId(),
                "",
                expected,
                actual,
                recognitions.stream().map(RevenueRecognition::revenueRecognitionId).toList(),
                List.of(fact.eventId()),
                "MATCHED",
                clock.instant(),
                causationIdOrEventId(envelope),
                envelope.correlationId()
            );
        }
    }

    private boolean hasRevenueRecognitionForSourceEvent(String sourceEventId) {
        return serviceRevenueBySourceEvent(sourceEventId).isPresent();
    }

    private Optional<RevenueRecognition> serviceRevenueBySourceEvent(String sourceEventId) {
        return projections.findAncillaryFinancialFact(sourceEventId)
            .stream()
            .flatMap(fact -> serviceRevenue(fact.journeyOrderId()).stream())
            .filter(recognition -> sourceEventId.equals(recognition.sourceEventId()))
            .findFirst();
    }

    private static String ancillaryFactKind(String eventType) {
        return switch (eventType) {
            case "AncillaryOrderItemFulfilled" -> "REVENUE_RECOGNITION";
            case "AncillaryOrderItemCancelled" -> "RETENTION_FACT";
            case "AncillaryOrderItemRefundPending" -> "REFUND_PENDING";
            case "AncillaryOrderItemRefunded" -> "REFUND_RECORDED";
            case "AncillaryFulfillmentFactRecorded" -> "SUPPLIER_COST_FACT";
            default -> "UNKNOWN";
        };
    }

    private static Money retainedAncillaryAmount(String eventType, Money payableAmount, Money refundableAmount, Money refundedAmount) {
        if ("AncillaryOrderItemCancelled".equals(eventType) && payableAmount != null && refundableAmount != null && sameCurrency(payableAmount, refundableAmount)) {
            Money retainedAmount = payableAmount.minus(refundableAmount);
            return retainedAmount.isNegative() ? Money.zero(payableAmount.currency()) : retainedAmount;
        }
        if ("AncillaryOrderItemRefunded".equals(eventType) && payableAmount != null && refundedAmount != null && sameCurrency(payableAmount, refundedAmount)) {
            Money retainedAmount = payableAmount.minus(refundedAmount);
            return retainedAmount.isNegative() ? Money.zero(payableAmount.currency()) : retainedAmount;
        }
        return null;
    }

    private static String ancillaryServiceType(Map<String, Object> payload) {
        return optionalText(payload, "serviceType")
            .or(() -> catalogSnapshot(payload).flatMap(snapshot -> optionalText(snapshot, "serviceType")))
            .orElseThrow(() -> new IllegalArgumentException("serviceType is required"));
    }

    private static String supplierReferenceForAncillary(Map<String, Object> payload) {
        return optionalText(payload, "providerRef")
            .or(() -> optionalText(payload, "entitlementRef"))
            .or(() -> fulfillmentFact(payload).flatMap(fact -> optionalText(fact, "providerRef")))
            .orElse(null);
    }

    private static Instant ancillaryOccurredAt(EventEnvelope envelope, Map<String, Object> payload) {
        String timestampField = switch (envelope.eventType()) {
            case "AncillaryOrderItemFulfilled" -> "fulfilledAt";
            case "AncillaryOrderItemCancelled" -> "cancelledAt";
            case "AncillaryOrderItemRefundPending" -> "requestedAt";
            case "AncillaryOrderItemRefunded" -> "refundedAt";
            case "AncillaryFulfillmentFactRecorded" -> null;
            default -> null;
        };
        if (timestampField != null) {
            return optionalText(payload, timestampField).map(Instant::parse).orElse(envelope.occurredAt());
        }
        return fulfillmentFact(payload)
            .flatMap(fact -> optionalText(fact, "occurredAt"))
            .map(Instant::parse)
            .orElse(envelope.occurredAt());
    }

    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> catalogSnapshot(Map<String, Object> payload) {
        Object value = payload.get("catalogSnapshot");
        return value instanceof Map<?, ?> raw ? Optional.of((Map<String, Object>) raw) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> fulfillmentFact(Map<String, Object> payload) {
        Object value = payload.get("fulfillmentFact");
        return value instanceof Map<?, ?> raw ? Optional.of((Map<String, Object>) raw) : Optional.empty();
    }

    private static Money benefitCostAmount(String eventType, Map<String, Object> payload) {
        return switch (eventType) {
            case "BenefitIssued" -> money(payload.get("issuedAmount"), "issuedAmount");
            case "BenefitRedeemed" -> money(payload.get("redeemedAmount"), "redeemedAmount");
            case "BenefitRedemptionReversed" -> money(payload.get("reversedAmount"), "reversedAmount").negate();
            case "BenefitRevoked" -> money(payload.get("revokedAmount"), "revokedAmount").negate();
            case "BenefitExpired" -> money(payload.get("expiredAmount"), "expiredAmount").negate();
            default -> throw new IllegalArgumentException("unsupported benefit cost event type " + eventType);
        };
    }

    private static String normalizedBenefitCostEventType(String eventType) {
        return switch (eventType) {
            case "BenefitRedemptionReversed" -> "BenefitReversed";
            default -> eventType;
        };
    }

    private static Instant benefitOccurredAt(EventEnvelope envelope, Map<String, Object> payload) {
        String timestampField = switch (envelope.eventType()) {
            case "BenefitIssued" -> "issuedAt";
            case "BenefitRedeemed" -> "redeemedAt";
            case "BenefitRedemptionReversed" -> "reversedAt";
            case "BenefitRevoked" -> "revokedAt";
            case "BenefitExpired" -> "expiredAt";
            default -> null;
        };
        if (timestampField == null) {
            return envelope.occurredAt();
        }
        return optionalText(payload, timestampField).map(Instant::parse).orElse(envelope.occurredAt());
    }

    private void rememberPaymentIntentOrderReference(EventEnvelope envelope,Map<String, Object> payload) {
        String orderId=text(payload,"businessRef");
        if("change".equals(payload.get("purpose")))orderId=changeOrder(envelope,payload,money(payload.get("amount"),"amount"));
        paymentIntentOrderReferences.save(text(payload, "paymentIntentId"), orderId);
    }

    private String changeOrder(EventEnvelope envelope,Map<String,Object> payload,Money amount) {
        if(changePayments==null)throw new IllegalStateException("change payment facts client is required");
        return changePayments.orderFor(text(payload,"businessRef"),text(payload,"paymentIntentId"),amount,envelope.correlationId());
    }

    private void rememberSegmentBookingOrderReference(Map<String, Object> payload) {
        segmentBookingOrderReferences.save(text(payload, "segmentBookingId"), text(payload, "journeyOrderId"));
    }

    private void recognizeCapturedPayment(EventEnvelope envelope, Map<String, Object> payload) {
        String paymentIntentId = text(payload, "paymentIntentId");
        String orderId = optionalText(payload, "businessRef")
            .or(() -> paymentIntentOrderReferences.findOrderReference(paymentIntentId))
            .orElseThrow(() -> new OutOfOrderEventException("PaymentCaptured received before order reference"));
        Money captured = money(payload.get("capturedAmount"), "capturedAmount");
        if("change".equals(payload.get("purpose"))) {
            orderId=changeOrder(envelope,payload,captured);
            service.lockOrder(orderId);
            paymentIntentOrderReferences.save(paymentIntentId,orderId);
            ensureChangeRevenue(orderId,text(payload,"businessRef"),paymentIntentId,captured,envelope.eventId(),envelope.occurredAt(),envelope);
            return;
        }
        paymentIntentOrderReferences.save(paymentIntentId,orderId);
        projections.saveCapture(orderId, new PaymentCaptureFact(orderId, paymentIntentId, captured, envelope.eventId(), envelope.occurredAt()));
        if (orderId.startsWith("aoi-")) return;
        String supplierId = supplierReferenceForOrder(orderId);
        RevenueRecognition recognition = RevenueRecognition.recognize(
            orderId,
            supplierId,
            "fare",
            captured,
            "payment-capture-v1",
            envelope.eventId(),
            envelope.occurredAt(),
            clock.instant(),
            causationIdOrEventId(envelope),
            envelope.correlationId()
        );
        service.saveAndPublish(recognition);
        service.accrueFeesForPaymentCapture(orderId, captured, Money.zero(captured.currency()), envelope.eventId(), clock.instant(), causationIdOrEventId(envelope), envelope.correlationId());
    }

    private void reconcileOperationalFact(EventEnvelope envelope, Map<String, Object> payload) {
        String segmentBookingId = optionalText(payload, "segmentBookingId").orElse("");
        Optional<String> orderReference = orderReferenceForOperationalFact(payload, segmentBookingId);
        if (orderReference.isEmpty()) {
            ReconciliationCase open = ReconciliationCase.open(
                "",
                "",
                "missing-in-platform",
                Money.zero(Currency.getInstance("CNY")),
                Money.zero(Currency.getInstance("CNY")),
                envelope.eventType() + " could not be assigned to an order; missing SegmentReservationRequested index for segmentBookingId " + segmentBookingId,
                clock.instant(),
                causationIdOrEventId(envelope),
                envelope.correlationId()
            );
            service.saveAndPublish(open);
            return;
        }

        String orderId = orderReference.get();
        PaymentCaptureFact capture = projections.findCapture(orderId).orElse(null);
        Money actual = capture == null ? Money.zero(Currency.getInstance("CNY")) : capture.amount();
        List<RevenueRecognition> recognitions = serviceRevenue(orderId);
        Money expected = sumOrZero(recognitions, actual.currency());
        if (capture == null || !sameMoney(expected, actual)) {
            ReconciliationCase open = ReconciliationCase.open(
                orderId,
                capture == null ? "" : capture.paymentIntentId(),
                capture == null ? "missing-in-platform" : "amount-mismatch",
                expected,
                actual,
                envelope.eventType() + " did not match recognized revenue",
                clock.instant(),
                causationIdOrEventId(envelope),
                envelope.correlationId()
            );
            service.saveAndPublish(open);
            return;
        }
        service.publishReconciliationCompleted(
            orderId,
            capture.paymentIntentId(),
            expected,
            actual,
            recognitions.stream().map(RevenueRecognition::revenueRecognitionId).toList(),
            List.of(capture.sourceEventId(), envelope.eventId()),
            "MATCHED",
            clock.instant(),
            causationIdOrEventId(envelope),
            envelope.correlationId()
        );
    }

    private Optional<String> orderReferenceForOperationalFact(Map<String, Object> payload, String segmentBookingId) {
        Optional<String> explicitOrderReference = optionalText(payload, "journeyOrderId")
            .or(() -> optionalText(payload, "orderId"))
            .or(() -> optionalText(payload, "businessRef"));
        if (explicitOrderReference.isPresent()) {
            return explicitOrderReference;
        }
        if (segmentBookingId.isBlank()) {
            return Optional.empty();
        }
        return segmentBookingOrderReferences.findOrderReference(segmentBookingId);
    }

    private void rememberApprovedRefund(Map<String, Object> payload) {
        String caseId = optionalText(payload, "caseId").orElse(optionalText(payload, "postSalesCaseId").orElse(null));
        if (caseId == null) {
            return;
        }
        Object approvedActions = payload.get("approvedActions");
        if (approvedActions instanceof Map<?, ?> actions && actions.get("refund") instanceof Map<?, ?> refund) {
            Money refundAmount = money(((Map<?, ?>) refund).get("amount"), "approvedActions.refund.amount");
            projections.saveApprovedRefund(caseId, refundAmount);
        }
    }

    private void applyPostSales(EventEnvelope envelope, Map<String, Object> payload) {
        String orderId = text(payload, "orderId");
        String caseId = text(payload, "caseId");
        if("CHANGE".equals(payload.get("caseType"))) {
            applyChangeFinance(envelope,orderId,caseId);
            return;
        }
        Money refund = projections.findApprovedRefund(caseId)
            .orElseThrow(() -> new OutOfOrderEventException("PostSalesApplied received before PostSalesApproved refund amount"));
        if (refund.isZero()) {
            if (shouldLogZeroRefundWarning()) {
                LOGGER.warn("service=finance-settlement eventId={} eventType={} orderId={} caseId={} zero refund amount; skipping revenue reversal",
                    envelope.eventId(), envelope.eventType(), orderId, caseId);
            }
            return;
        }
        PaymentCaptureFact capture = projections.findCapture(orderId).orElse(null);
        Optional<RevenueRecognition> matchingRecognition = serviceRevenue(orderId).stream()
            .filter(recognition -> !recognition.reversed())
            .filter(recognition -> sameCurrency(recognition.amount(), refund))
            .filter(recognition -> sameMoney(recognition.amount(), refund) || recognition.amount().compareTo(refund) >= 0)
            .findFirst();
        if (matchingRecognition.isEmpty()) {
            ReconciliationCase open = ReconciliationCase.open(
                orderId,
                capture == null ? "" : capture.paymentIntentId(),
                "refund-lag",
                refund.negate(),
                Money.zero(refund.currency()),
                "PostSalesApplied refund could not be matched to recognized revenue",
                clock.instant(),
                causationIdOrEventId(envelope),
                envelope.correlationId()
            );
            service.saveAndPublish(open);
            return;
        }
        RevenueRecognition originalRecognition = matchingRecognition.get();
        int firstUnpublishedEventIndex = originalRecognition.domainEvents().size();
        originalRecognition.reverse(
            "post-sales refund applied",
            envelope.eventId(),
            refund,
            clock.instant(),
            causationIdOrEventId(envelope),
            causationIdOrEventId(envelope),
            envelope.correlationId()
        );
        service.saveAndPublish(originalRecognition, firstUnpublishedEventIndex);
        service.accrueFeesForRefund(orderId, refund, capture == null ? refund.currency() : capture.amount().currency(), envelope.eventId(), clock.instant(), causationIdOrEventId(envelope), envelope.correlationId());
        Money expected = capture == null ? refund.negate() : capture.amount().minus(refund);
        service.publishReconciliationCompleted(
            orderId,
            capture == null ? "" : capture.paymentIntentId(),
            expected,
            expected,
            serviceRevenue(orderId).stream().map(RevenueRecognition::revenueRecognitionId).toList(),
            capture == null ? List.of(envelope.eventId()) : List.of(capture.sourceEventId(), envelope.eventId()),
            "MATCHED",
            clock.instant(),
            causationIdOrEventId(envelope),
            envelope.correlationId()
        );
    }

    private void applyChangeFinance(EventEnvelope envelope,String orderId,String caseId) {
        if(changePayments==null)throw new IllegalStateException("change payment facts client is required");
        var change=changePayments.read("post-sales","/api/v1/post-sales-cases/"+caseId,envelope.correlationId());
        if(!caseId.equals(change.path("caseId").asText()) || !orderId.equals(change.path("journeyOrderId").asText())
            || !"CHANGE".equals(change.path("caseType").asText()) || !"APPLIED".equals(change.path("status").asText()))throw new DomainRuleViolation("applied change financial reference mismatch");
        service.lockOrder(orderId);
        Money expected=jsonMoney(change.path("decision").path("refundableAmount"));
        Money due=jsonMoney(change.path("decision").path("amountDue"));
        var revenues=serviceRevenue(orderId);
        if(!due.isZero()) {
            String paymentId=change.path("changeOperations").path("difference-payment").path("paymentIntentId").asText();
            if(!orderId.equals(changePayments.orderFor(caseId,paymentId,due,envelope.correlationId())))throw new DomainRuleViolation("change payment belongs to another order");
            var payment=changePayments.read("payment","/api/v1/payment-intents/"+paymentId,envelope.correlationId());
            if(!"CAPTURED".equals(payment.path("status").asText()) || !due.equals(jsonMoney(payment.path("capturedAmount"))))throw new OutOfOrderEventException("change difference is not captured");
            var facts=changePayments.read("payment","/api/v1/internal/payment-intents/"+paymentId+"/captured-facts",envelope.correlationId());
            if(facts.path("events").size()!=1)throw new DomainRuleViolation("change payment requires one authoritative captured event");
            var capturedEvent=facts.path("events").get(0);
            var recognition=ensureChangeRevenue(orderId,caseId,paymentId,due,capturedEvent.path("eventId").asText(),Instant.parse(capturedEvent.path("occurredAt").asText()),envelope);
            if(!recognition.amount().equals(due))throw new DomainRuleViolation("change difference recognition amount mismatch");
        }
        var refunds=changePayments.read("payment","/api/v1/refunds?businessCaseRef="+caseId,envelope.correlationId());
        if(!refunds.path("items").isArray() || refunds.path("total").asInt(-1)!=refunds.path("items").size())throw new DomainRuleViolation("change refund list is incomplete");
        Money total=Money.zero(expected.currency());
        var refs=new java.util.HashSet<String>();
        var matched=new java.util.LinkedHashMap<String,RevenueRecognition>();
        var amounts=new java.util.LinkedHashMap<String,Money>();
        for(var refund:refunds.path("items")) {
            String refundId=refund.path("refundId").asText(),paymentId=refund.path("paymentIntentId").asText();
            if(refundId.isBlank() || !refs.add(refundId) || !caseId.equals(refund.path("businessCaseRef").asText()))throw new DomainRuleViolation("change refund identity mismatch");
            if(!"SETTLED".equals(refund.path("status").asText()))throw new OutOfOrderEventException("change refund has not settled");
            Money amount=jsonMoney(refund.path("amount"));
            var facts=changePayments.read("payment","/api/v1/internal/payment-intents/"+paymentId+"/captured-facts",envelope.correlationId());
            var sourceIds=new java.util.HashSet<String>();
            facts.path("events").forEach(event->sourceIds.add(event.path("eventId").asText()));
            var recognition=revenues.stream().filter(item->sourceIds.contains(item.sourceEventId())).findFirst()
                .orElseThrow(()->new OutOfOrderEventException("refunded payment revenue is not assigned to this order"));
            if(!recognition.amount().equals(jsonMoney(facts.path("capturedAmount"))) || jsonMoney(facts.path("refundedAmount")).compareTo(amount)<0)throw new DomainRuleViolation("refund exceeds its actual payment facts");
            matched.put(refundId,recognition);amounts.put(refundId,amount);total=total.plus(amount);
        }
        if(!total.equals(expected))throw new DomainRuleViolation("settled change refunds differ from approved total");
        for(String refundId:matched.keySet()) {
            var recognition=matched.get(refundId);
            int first=recognition.domainEvents().size();
            recognition.reverse("change fare difference refunded",refundId,amounts.get(refundId),clock.instant(),causationIdOrEventId(envelope),causationIdOrEventId(envelope),envelope.correlationId());
            if(recognition.domainEvents().size()>first)service.saveAndPublish(recognition,first);
        }
        service.recordChangeFee(orderId,jsonMoney(change.path("decision").path("changeAssessment").path("changeFee")),caseId,envelope.correlationId());
    }

    private RevenueRecognition ensureChangeRevenue(String orderId,String caseId,String paymentId,Money amount,String sourceId,Instant capturedAt,EventEnvelope envelope) {
        var existing=serviceRevenue(orderId).stream().filter(item->paymentId.equals(item.orderItemId()) && "change-payment".equals(item.componentCode())).findFirst();
        if(existing.isPresent()) {
            if(!existing.get().amount().equals(amount) || !existing.get().sourceEventId().equals(sourceId))throw new DomainRuleViolation("change recognition differs from captured payment");
            return existing.get();
        }
        var misplaced=serviceRevenue(caseId).stream().filter(item->item.sourceEventId().equals(sourceId)).toList();
        if(misplaced.size()>1)throw new DomainRuleViolation("payment has duplicate revenue requiring reconciliation");
        for(var previous:misplaced) {
            if(!previous.amount().equals(amount) || !previous.reversedAmount().isZero())throw new DomainRuleViolation("change payment revenue requires checked reversal reconciliation");
            int first=previous.domainEvents().size();
            previous.reverse("payment revenue reassigned to journey order","reassign:"+paymentId,amount,clock.instant(),causationIdOrEventId(envelope),causationIdOrEventId(envelope),envelope.correlationId());
            service.saveAndPublish(previous,first);
        }
        var recognition=RevenueRecognition.recognize(orderId,paymentId,"change-payment",amount,"change-payment-v1",sourceId,capturedAt,
            clock.instant(),causationIdOrEventId(envelope),envelope.correlationId());
        service.saveAndPublish(recognition);
        paymentIntentOrderReferences.save(paymentId,orderId);
        return recognition;
    }

    private static Money jsonMoney(com.fasterxml.jackson.databind.JsonNode node) {
        if(!node.path("minorUnits").isIntegralNumber() || !node.path("currency").isTextual())throw new DomainRuleViolation("financial money is incomplete");
        Currency currency=Currency.getInstance(node.path("currency").asText());
        return new Money(currency,BigDecimal.valueOf(node.path("minorUnits").asLong(),currency.getDefaultFractionDigits()));
    }

    private String supplierReferenceForOrder(String orderId) {
        return segmentBookingOrderReferences.findOrderReference("supplier:" + orderId).orElse(orderId);
    }

    private List<RevenueRecognition> serviceRevenue(String orderId) {
        return service.findRevenueRecognitionsByOrderId(orderId);
    }

    private static Money sumOrZero(List<RevenueRecognition> recognitions, Currency currency) {
        return recognitions.stream().map(RevenueRecognition::netAmount).reduce(Money.zero(currency), Money::plus);
    }

    private static boolean sameMoney(Money left, Money right) {
        return sameCurrency(left, right) && left.compareTo(right) == 0;
    }

    private static boolean shouldLogZeroRefundWarning() {
        int sequence = ZERO_REFUND_WARNING_SEQUENCE.incrementAndGet();
        return sequence == 1 || sequence % ZERO_REFUND_WARNING_SAMPLE_RATE == 0;
    }

    private static boolean sameCurrency(Money left, Money right) {
        return left.currency().equals(right.currency());
    }

    private static String orderItemReference(Map<String, Object> payload, String orderId) {
        return optionalText(payload, "orderItemId").orElse(orderId);
    }

    private static String causationIdOrEventId(EventEnvelope envelope) {
        return envelope.causationId() == null ? envelope.eventId() : envelope.causationId();
    }

    private static Number number(Map<String, Object> payload, String name) {
        Object value = payload.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return number;
    }

    private static String text(Map<String, Object> payload, String name) {
        Object value = payload.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return text;
    }

    private static Optional<String> optionalText(Map<String, Object> payload, String name) {
        Object value = payload.get(name);
        return value instanceof String text && !text.isBlank() ? Optional.of(text) : Optional.empty();
    }

    private static Optional<Money> optionalMoney(Map<String, Object> payload, String name) {
        Object value = payload.get(name);
        return value == null ? Optional.empty() : Optional.of(money(value, name));
    }

    @SuppressWarnings("unchecked")
    private static Money money(Object value, String fieldName) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        Map<String, Object> amount = (Map<String, Object>) raw;
        String currencyCode = text(amount, "currency");
        Object minorUnitsValue = amount.get("minorUnits");
        if (!(minorUnitsValue instanceof Number minorUnits)) {
            throw new IllegalArgumentException(fieldName + ".minorUnits is required");
        }
        int fractionDigits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
        BigDecimal majorUnits = BigDecimal.valueOf(minorUnits.longValue()).movePointLeft(fractionDigits);
        return Money.of(currencyCode, majorUnits.toPlainString());
    }

    public record PaymentCaptureFact(String orderId, String paymentIntentId, Money amount, String sourceEventId, Instant occurredAt) {
        public PaymentCaptureFact(String paymentIntentId, Money amount, String sourceEventId) {
            this("", paymentIntentId, amount, sourceEventId, Instant.EPOCH);
        }

        public PaymentCaptureFact(String orderId, String paymentIntentId, Money amount, String sourceEventId) {
            this(orderId, paymentIntentId, amount, sourceEventId, Instant.EPOCH);
        }
    }

    private static final class OutOfOrderEventException extends RuntimeException {
        private OutOfOrderEventException(String message) {
            super(message);
        }
    }
}
