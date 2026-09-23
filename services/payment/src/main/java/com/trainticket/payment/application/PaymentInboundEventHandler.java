package com.trainticket.payment.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.payment.domain.DomainRuleViolation;
import com.trainticket.payment.domain.Money;
import java.util.Objects;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Component
public class PaymentInboundEventHandler implements EventSubscriber.EventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentInboundEventHandler.class);

    private final ConsumedEventDeduplicator deduplicator;
    private final PaymentCommandService paymentCommands;

    public PaymentInboundEventHandler(ConsumedEventDeduplicator deduplicator, PaymentCommandService paymentCommands) {
        this.deduplicator = Objects.requireNonNull(deduplicator, "deduplicator is required");
        this.paymentCommands = Objects.requireNonNull(paymentCommands, "paymentCommands are required");
    }

    @Override
    @Transactional
    public HandlerResult handle(EventEnvelope envelope) {
        try {
            if (!deduplicator.recordIfNew(envelope.eventId())) {
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
            //
            // The transaction is still rolled back: the nested command
            // transaction may already be rollback-only, so nothing can be
            // committed on this path regardless of classification.
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
        paymentCommands.recordReservationPaymentRequest(
            envelope.eventId(),
            payload.requiredText("segmentBookingId"),
            payload.requiredText("journeyOrderId"),
            payload.requiredText("segmentRef"),
            payload.requiredText("travelerRef"),
            payload.requiredText("idempotencyKey"),
            envelope.correlationId(),
            envelope.occurredAt()
        );
    }

    private void handleChannelOrderSucceeded(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        Money amount = "ChannelOrderRecoveryDetected".equals(envelope.eventType())
            ? payload.requiredMoney("recoveredAmount")
            : payload.requiredMoney("succeededAmount");
        paymentCommands.captureIntentFromChannel(
            payload.requiredText("paymentIntentId"),
            amount,
            payload.requiredText("channel"),
            payload.requiredText("channelTransactionId"),
            payload.requiredText("channelOrderId"),
            envelope.eventId(),
            envelope.correlationId()
        );
    }

    private void handleChannelOrderFailed(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        paymentCommands.failIntentFromChannel(
            payload.requiredText("paymentIntentId"),
            envelope.eventType(),
            envelope.eventId(),
            envelope.correlationId()
        );
    }

    private void handleChannelRefundSucceeded(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        Money amount = "ChannelRefundRecoveryDetected".equals(envelope.eventType())
            ? payload.requiredMoney("recoveredAmount")
            : payload.requiredMoney("succeededAmount");
        paymentCommands.settleRefundFromChannel(
            payload.requiredText("refundId"),
            payload.requiredText("paymentIntentId"),
            amount,
            payload.requiredText("channelRefundId"),
            payload.optionalText("channelOrderId"),
            payload.optionalText("channel"),
            payload.optionalText("originalChannelTransactionId"),
            payload.requiredText("channelRefundTransactionId"),
            envelope.eventId(),
            envelope.correlationId()
        );
    }

    private void handleChannelRefundFailed(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        paymentCommands.failRefundFromChannel(
            payload.requiredText("refundId"),
            envelope.eventType(),
            envelope.eventId(),
            envelope.correlationId()
        );
    }

    private void handlePostSalesApproved(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        if(java.util.Set.of("CHANGE","COMPENSATION").contains(String.valueOf(payload.optionalDecisionKindFromApprovedActions())))return;
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
            for(Object raw:refunds){
                if(!(raw instanceof Map<?,?> allocation) || !(allocation.get("amount") instanceof Map<?,?> value))throw new IllegalArgumentException("refund payment allocation is invalid");
                String paymentId=String.valueOf(allocation.get("paymentIntentId"));
                Money allocated=Money.fromMinorUnits(((Number)value.get("minorUnits")).longValue(),String.valueOf(value.get("currency")));
                requestRefund(paymentId,allocated,reason==null?"post-sales-approved":reason,caseId,caseId+":"+paymentId,envelope.correlationId());
            }
            return;
        }
        String intentId = payload.optionalPaymentIntentIdFromApprovedActions();
        if (intentId == null) {
            String orderRef = payload.orderRefFromApprovedActions();
            intentId = orderRef == null ? null : paymentCommands.findIntentIdByBusinessRef(orderRef).orElse(null);
            if (intentId == null && orderRef != null) {
                Money approved=payload.moneyFromApprovedActions();
                if(approved!=null && !approved.isZero())throw new IllegalStateException("approved refund awaits payment intent for order "+orderRef);
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
            return;
        }
        Money amount = payload.moneyFromApprovedActions();
        if (amount == null || amount.isZero()) {
            String decisionKind = payload.optionalDecisionKindFromApprovedActions();
            LOGGER.info("payment received PostSalesApproved without a payable refund: case={} intent={} eventId={} decisionKind={} amount={}",
                caseId, intentId, envelope.eventId(), decisionKind, amount);
            return;
        }
        LOGGER.info("payment requesting refund from PostSalesApproved case={} intent={} amount={} reason={}",
            caseId, intentId, amount, reason);
        requestRefund(intentId, amount, reason == null ? "post-sales-approved" : reason, caseId, caseId, envelope.correlationId());
    }

    private void handleAncillaryOrderItemRefundPending(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        String recommendation = payload.requiredText("recommendation");
        if ("NO_REFUND".equals(recommendation) || "MANUAL_REVIEW".equals(recommendation)) {
            return;
        }
        if (!"FULL_REFUND".equals(recommendation) && !"PARTIAL_REFUND".equals(recommendation)) {
            throw new AckSkipEventException("unknown ancillary refund recommendation " + recommendation);
        }
        Money amount = payload.requiredMoney("refundableAmount");
        if (amount.isZero()) {
            return;
        }
        String intentId = payload.requiredText("paymentIntentId");
        var intent = paymentCommands.getIntent(intentId);
        if (!intent.businessRef().equals(payload.requiredText("ancillaryOrderItemId")) || !"ancillary".equals(intent.purpose())) {
            throw new IllegalArgumentException("Ancillary refund must reference its own ancillary payment intent");
        }
        if (!intent.amount().currency().equals(amount.currency()) || amount.isGreaterThan(intent.capturedAmount()))
            throw new IllegalArgumentException("Ancillary refund amount exceeds its captured payment");
        requestRefund(
            intentId,
            amount,
            payload.requiredText("reasonCode"),
            businessCaseRef(payload),
            "ancillary-refund:" + envelope.eventId(),
            envelope.correlationId()
        );
    }

    private void requestRefund(String intentId, Money amount, String reason, String businessCaseRef, String idempotencyKey, String correlationId) {
        com.trainticket.payment.domain.ChannelRef route = paymentCommands.getIntent(intentId).channelRef();
        if (route != null && route.channel() != null && route.channelOrderId() != null && route.channelTransactionId() != null) {
            paymentCommands.requestRefund(intentId, amount, reason, businessCaseRef, idempotencyKey, correlationId, route);
            return;
        }
        paymentCommands.requestRefund(intentId, amount, reason, businessCaseRef, idempotencyKey, correlationId);
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
