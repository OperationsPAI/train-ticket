package com.trainticket.payment.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.DomainRuleViolation;
import com.trainticket.payment.domain.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PaymentInboundEventHandler implements EventSubscriber.EventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentInboundEventHandler.class);
    private static final Set<String> NON_PAYABLE_DECISIONS = Set.of("CHANGE", "COMPENSATION");

    private final ConsumedEventDeduplicator deduplicator;
    private final PaymentCommandService paymentCommands;
    private final TransactionTemplate transactionTemplate;

    public PaymentInboundEventHandler(ConsumedEventDeduplicator deduplicator, PaymentCommandService paymentCommands) {
        this(deduplicator, paymentCommands, Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentInboundEventHandler(ConsumedEventDeduplicator deduplicator, PaymentCommandService paymentCommands, Optional<PlatformTransactionManager> transactionManager) {
        this.deduplicator = Objects.requireNonNull(deduplicator, "deduplicator is required");
        this.paymentCommands = Objects.requireNonNull(paymentCommands, "paymentCommands are required");
        this.transactionTemplate = transactionManager.map(TransactionTemplate::new).orElse(null);
    }

    @Override
    public HandlerResult handle(EventEnvelope envelope) {
        try {
            if (deduplicator.isProcessed(envelope.eventId())) {
                return HandlerResult.SUCCESS;
            }
            dispatch(envelope);
            return HandlerResult.SUCCESS;
        } catch (AckSkipEventException exception) {
            LOGGER.warn("eventType={} eventId={} ack-skipped: {}", envelope.eventType(), envelope.eventId(), exception.getMessage());
            return HandlerResult.SUCCESS;
        } catch (DomainRuleViolation exception) {
            // A DomainRuleViolation means THIS CONTEXT's state cannot accept the
            // event, not that the event violated its own contract. Under the
            // taxonomy in docs/00-current-status.md, FATAL is reserved for the
            // latter; a conformant event meeting unknown or advanced local state
            // is ack-skipped with a WARN.
            LOGGER.warn("eventType={} eventId={} rejected by local state; ack-skipped without applying",
                envelope.eventType(), envelope.eventId(), exception);
            rollbackCurrentTransactionIfActive();
            return HandlerResult.SUCCESS;
        } catch (IllegalArgumentException exception) {
            // A malformed or unparseable payload IS a contract violation: no
            // amount of retrying or waiting will make it processable.
            LOGGER.warn("eventType={} eventId={} violates its own contract; dead-lettering", envelope.eventType(), envelope.eventId(), exception);
            rollbackCurrentTransactionIfActive();
            return HandlerResult.FATAL_FAILURE;
        } catch (RuntimeException exception) {
            LOGGER.warn("eventType={} eventId={} failed; will retry", envelope.eventType(), envelope.eventId(), exception);
            rollbackCurrentTransactionIfActive();
            return HandlerResult.TRANSIENT_FAILURE;
        }
    }

    /**
     * Runs one branch effect and the deduplication record in the same transaction, so a
     * failure leaves no record behind and the retry processes the event again.
     */
    private void applyOnce(EventEnvelope envelope, Runnable effect) {
        runInTransaction(() -> {
            effect.run();
            deduplicator.recordIfNew(envelope.eventId());
        });
    }

    /** Records an event that needs no business effect, so a duplicate delivery is skipped. */
    private void markProcessed(EventEnvelope envelope) {
        runInTransaction(() -> deduplicator.recordIfNew(envelope.eventId()));
    }

    private void runInTransaction(Runnable work) {
        if (transactionTemplate == null) {
            work.run();
            return;
        }
        transactionTemplate.executeWithoutResult(status -> work.run());
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (transactionTemplate == null) {
            return work.get();
        }
        return transactionTemplate.execute(status -> work.get());
    }

    private static void rollbackCurrentTransactionIfActive() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (RuntimeException ignored) {
            // Unit tests may run with a no-op transaction manager outside a real transaction.
        }
    }

    private void dispatch(EventEnvelope envelope) {
        if ("SegmentReservationRequested".equals(envelope.eventType())) {
            handleSegmentReservationRequested(envelope);
        } else if ("PostSalesApproved".equals(envelope.eventType())) {
            handlePostSalesApproved(envelope);
        } else if ("ChannelOrderSucceeded".equals(envelope.eventType()) || "ChannelOrderRecoveryDetected".equals(envelope.eventType())) {
            handleChannelOrderSucceeded(envelope);
        } else if ("ChannelOrderFailed".equals(envelope.eventType()) || "ChannelOrderMissed".equals(envelope.eventType())) {
            handleChannelOrderFailed(envelope);
        } else if ("ChannelRefundSucceeded".equals(envelope.eventType()) || "ChannelRefundRecoveryDetected".equals(envelope.eventType())) {
            handleChannelRefundSucceeded(envelope);
        } else if ("ChannelRefundFailed".equals(envelope.eventType()) || "ChannelRefundMissed".equals(envelope.eventType())) {
            handleChannelRefundFailed(envelope);
        } else if ("AncillaryOrderItemRefundPending".equals(envelope.eventType())) {
            handleAncillaryOrderItemRefundPending(envelope);
        }
    }

    private void handleSegmentReservationRequested(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        String segmentBookingId = payload.requiredText("segmentBookingId");
        String journeyOrderId = payload.requiredText("journeyOrderId");
        String segmentRef = payload.requiredText("segmentRef");
        String travelerRef = payload.requiredText("travelerRef");
        String idempotencyKey = payload.requiredText("idempotencyKey");
        applyOnce(envelope, () -> paymentCommands.recordReservationPaymentRequest(
            envelope.eventId(), segmentBookingId, journeyOrderId, segmentRef, travelerRef, idempotencyKey,
            envelope.correlationId(), envelope.occurredAt()));
    }

    private void handleChannelOrderSucceeded(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        Money amount = "ChannelOrderRecoveryDetected".equals(envelope.eventType())
            ? payload.requiredMoney("recoveredAmount")
            : payload.requiredMoney("succeededAmount");
        String paymentIntentId = payload.requiredText("paymentIntentId");
        String channel = payload.requiredText("channel");
        String channelTransactionId = payload.requiredText("channelTransactionId");
        String channelOrderId = payload.requiredText("channelOrderId");
        applyOnce(envelope, () -> paymentCommands.captureIntentFromChannel(
            paymentIntentId, amount, channel, channelTransactionId, channelOrderId,
            envelope.eventId(), envelope.correlationId()));
    }

    private void handleChannelOrderFailed(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        String paymentIntentId = payload.requiredText("paymentIntentId");
        applyOnce(envelope, () -> paymentCommands.failIntentFromChannel(
            paymentIntentId, envelope.eventType(), envelope.eventId(), envelope.correlationId()));
    }

    private void handleChannelRefundSucceeded(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        Money amount = "ChannelRefundRecoveryDetected".equals(envelope.eventType())
            ? payload.requiredMoney("recoveredAmount")
            : payload.requiredMoney("succeededAmount");
        String refundId = payload.requiredText("refundId");
        String paymentIntentId = payload.requiredText("paymentIntentId");
        String channelRefundId = payload.requiredText("channelRefundId");
        String channelOrderId = payload.optionalText("channelOrderId");
        String channel = payload.optionalText("channel");
        String originalChannelTransactionId = payload.optionalText("originalChannelTransactionId");
        String channelRefundTransactionId = payload.requiredText("channelRefundTransactionId");
        applyOnce(envelope, () -> paymentCommands.settleRefundFromChannel(
            refundId, paymentIntentId, amount, channelRefundId, channelOrderId, channel,
            originalChannelTransactionId, channelRefundTransactionId, envelope.eventId(), envelope.correlationId()));
    }

    private void handleChannelRefundFailed(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        String refundId = payload.requiredText("refundId");
        applyOnce(envelope, () -> paymentCommands.failRefundFromChannel(
            refundId, envelope.eventType(), envelope.eventId(), envelope.correlationId()));
    }

    private void handlePostSalesApproved(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        if (NON_PAYABLE_DECISIONS.contains(String.valueOf(payload.optionalDecisionKindFromApprovedActions()))) {
            markProcessed(envelope);
            return;
        }
        String caseId = payload.requiredText("caseId");
        String reason = payload.optionalText("reason");
        if(envelope.payload() instanceof Map<?,?> data && data.get("approvedActions") instanceof Map<?,?> actions && actions.get("refunds") instanceof java.util.List<?> refunds){
            Money total=Money.fromMinorUnits(0,payload.moneyFromApprovedActions().currency().getCurrencyCode());
            var seen=new java.util.HashSet<String>();
            for(Object raw:refunds){
                if(!(raw instanceof Map<?,?> allocation) || !(allocation.get("amount") instanceof Map<?,?> value)
                    || !(allocation.get("paymentIntentId") instanceof String paymentId) || !seen.add(paymentId))throw new IllegalArgumentException("refund payment allocation is invalid or duplicated");
                Money amount=Money.fromMinorUnits(((Number)value.get("minorUnits")).longValue(),String.valueOf(value.get("currency")));
                if(amount.isZero())throw new IllegalArgumentException("refund allocation must be positive");
                total=total.add(amount);
            }
            if(!total.equals(payload.moneyFromApprovedActions()))throw new IllegalArgumentException("refund allocations differ from the approved amount");
            List<RefundSubmission> submissions=new ArrayList<>();
            for(Object raw:refunds){
                if(!(raw instanceof Map<?,?> allocation) || !(allocation.get("amount") instanceof Map<?,?> value))throw new IllegalArgumentException("refund payment allocation is invalid");
                String paymentId=String.valueOf(allocation.get("paymentIntentId"));
                Money allocated=Money.fromMinorUnits(((Number)value.get("minorUnits")).longValue(),String.valueOf(value.get("currency")));
                submissions.add(new RefundSubmission(paymentId,allocated,reason==null?"post-sales-approved":reason,caseId,caseId+":"+paymentId));
            }
            submitRefunds(envelope,submissions);
            return;
        }
        String intentId = payload.optionalPaymentIntentIdFromApprovedActions();
        if (intentId == null) {
            String orderRef = payload.orderRefFromApprovedActions();
            intentId = orderRef == null ? null : paymentCommands.findIntentIdByBusinessRef(orderRef).orElse(null);
            if (intentId == null && orderRef != null) {
                Money approved=payload.moneyFromApprovedActions();
                if(approved!=null && !approved.isZero())throw new IllegalStateException("approved refund awaits payment intent for order "+orderRef);
                markProcessed(envelope);
                return;
            }
        }
        if (intentId == null) {
            // Approval does not reference any payment this context knows about.
            // DEBUG, not WARN: a COMPENSATION or entitlement-only approval
            // legitimately names no payment, so this is a normal no-op rather than
            // a dropped refund.
            LOGGER.debug("payment ignoring PostSalesApproved case={} eventId={}: the approval "
                    + "references no payment intent or order", caseId, envelope.eventId());
            markProcessed(envelope);
            return;
        }
        Money amount = payload.moneyFromApprovedActions();
        if (amount == null || amount.isZero()) {
            String decisionKind = payload.optionalDecisionKindFromApprovedActions();
            LOGGER.info("payment received PostSalesApproved without a payable refund: case={} intent={} eventId={} decisionKind={} amount={}",
                caseId, intentId, envelope.eventId(), decisionKind, amount);
            markProcessed(envelope);
            return;
        }
        LOGGER.info("payment requesting refund from PostSalesApproved case={} intent={} amount={} reason={}",
            caseId, intentId, amount, reason);
        submitRefunds(envelope, List.of(new RefundSubmission(intentId, amount, reason == null ? "post-sales-approved" : reason, caseId, caseId)));
    }

    private void handleAncillaryOrderItemRefundPending(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        String recommendation = payload.requiredText("recommendation");
        if ("NO_REFUND".equals(recommendation) || "MANUAL_REVIEW".equals(recommendation)) {
            markProcessed(envelope);
            return;
        }
        if (!"FULL_REFUND".equals(recommendation) && !"PARTIAL_REFUND".equals(recommendation)) {
            throw new AckSkipEventException("unknown ancillary refund recommendation " + recommendation);
        }
        Money amount = payload.requiredMoney("refundableAmount");
        if (amount.isZero()) {
            markProcessed(envelope);
            return;
        }
        String intentId = payload.requiredText("paymentIntentId");
        var intent = paymentCommands.getIntent(intentId);
        if (!intent.businessRef().equals(payload.requiredText("ancillaryOrderItemId")) || !"ancillary".equals(intent.purpose())) {
            throw new IllegalArgumentException("Ancillary refund must reference its own ancillary payment intent");
        }
        if (!intent.amount().currency().equals(amount.currency()) || amount.isGreaterThan(intent.capturedAmount()))
            throw new IllegalArgumentException("Ancillary refund amount exceeds its captured payment");
        submitRefunds(envelope, List.of(new RefundSubmission(
            intentId, amount, payload.requiredText("reasonCode"), businessCaseRef(payload), "ancillary-refund:" + envelope.eventId())));
    }

    /**
     * Prepares every refund in one transaction, hands each routed refund to the channel
     * outside any transaction, then applies the channel results together with the event
     * deduplication record. A failure before or during that last transaction leaves no
     * deduplication record, so the retry submits to the channel again on the same keys.
     */
    private void submitRefunds(EventEnvelope envelope, List<RefundSubmission> requests) {
        // The three phases are timed together and reported when the whole handler
        // takes longer than a second: a refund that reaches the owning case late
        // is either waiting for a connection, waiting for the channel, or waiting
        // for the apply transaction, and the three have different fixes. Measured
        // before this: refund handlers took four to sixteen seconds during a burst
        // while the database and the channel were both idle.
        long startedAt = System.nanoTime();
        List<PaymentCommandService.RefundHandoff> prepared = inTransaction(() -> {
            List<PaymentCommandService.RefundHandoff> handoffs = new ArrayList<>();
            for (RefundSubmission request : requests) {
                ChannelRef route = paymentCommands.getIntent(request.paymentIntentId()).channelRef();
                if (route == null || route.channel() == null || route.channelOrderId() == null || route.channelTransactionId() == null) {
                    paymentCommands.requestRefund(request.paymentIntentId(), request.amount(), request.reason(), request.businessCaseRef(), request.idempotencyKey(), envelope.correlationId());
                    continue;
                }
                handoffs.add(paymentCommands.prepareRefundSubmission(
                    request.paymentIntentId(), request.amount(), request.reason(), request.businessCaseRef(), request.idempotencyKey(), envelope.correlationId(), route));
            }
            return handoffs;
        });
        long preparedAt = System.nanoTime();
        List<AppliedRefundHandoff> applied = new ArrayList<>();
        for (PaymentCommandService.RefundHandoff handoff : prepared) {
            if (!handoff.needsChannel()) continue;
            applied.add(new AppliedRefundHandoff(handoff, paymentCommands.handoffRefund(handoff, envelope.correlationId())));
        }
        long handedOffAt = System.nanoTime();
        runInTransaction(() -> {
            for (AppliedRefundHandoff application : applied) {
                paymentCommands.applyRefundHandoff(application.handoff(), application.channelHandoff());
            }
            deduplicator.recordIfNew(envelope.eventId());
        });
        long appliedAt = System.nanoTime();
        long totalMillis = (appliedAt - startedAt) / 1_000_000;
        if (totalMillis > 1_000) {
            LOGGER.warn("payment refund handling is slow: case={} refunds={} prepare={}ms channel={}ms apply={}ms total={}ms",
                envelope.payload() instanceof Map<?, ?> data ? data.get("caseId") : null,
                requests.size(),
                (preparedAt - startedAt) / 1_000_000,
                (handedOffAt - preparedAt) / 1_000_000,
                (appliedAt - handedOffAt) / 1_000_000,
                totalMillis);
        }
    }

    private record RefundSubmission(String paymentIntentId, Money amount, String reason, String businessCaseRef, String idempotencyKey) {
    }

    private record AppliedRefundHandoff(PaymentCommandService.RefundHandoff handoff, PaymentChannelClient.HandoffRefund channelHandoff) {
    }

    private static String businessCaseRef(InboundEventPayload payload) {
        String postSalesCaseId = payload.optionalTextValue("postSalesCaseId");
        if (postSalesCaseId != null) {
            return postSalesCaseId;
        }
        return "ancillary:" + payload.requiredText("ancillaryOrderItemId");
    }

    private static final class AckSkipEventException extends RuntimeException {
        private AckSkipEventException(String message) {
            super(message);
        }
    }
}
