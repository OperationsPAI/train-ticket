package com.trainticket.payment.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.payment.domain.DomainRuleViolation;
import com.trainticket.payment.domain.Money;
import java.util.Objects;
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
        } catch (DomainRuleViolation | IllegalArgumentException exception) {
            // The nested command transaction may already be rollback-only, so a
            // clean commit of SUCCESS is impossible here anyway; classify FATAL
            // and roll everything back together.
            LOGGER.warn("eventType={} eventId={} rejected as non-processable", envelope.eventType(), envelope.eventId(), exception);
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
        String caseId = payload.requiredText("caseId");
        String reason = payload.optionalText("reason");
        String intentId = payload.optionalPaymentIntentIdFromApprovedActions();
        if (intentId == null) {
            String orderRef = payload.orderRefFromApprovedActions();
            intentId = orderRef == null ? null : paymentCommands.findIntentIdByBusinessRef(orderRef).orElse(null);
            if (intentId == null && orderRef != null) {
                LOGGER.warn("payment ignoring PostSalesApproved case={} eventId={}: no payment intent "
                        + "is registered for order {}", caseId, envelope.eventId(), orderRef);
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
            // CHANGE decisions routinely approve with a zero refund amount:
            // nothing to refund, so this event is a no-op for payment.
            //
            // But a REFUND decision arriving with zero is the visible end of the
            // post-sales policy-context failure (missing context -> departureTime
            // defaults to now -> AFTER_DEPARTURE_NON_REFUNDABLE -> 100% penalty).
            // Distinguishing the two here is what turns "the customer was never
            // refunded and nothing logged it" into one greppable line.
            String decisionKind = payload.optionalDecisionKindFromApprovedActions();
            if ("REFUND".equals(decisionKind)) {
                LOGGER.warn("payment received a REFUND approval with a ZERO amount: case={} order-intent={} "
                        + "eventId={} amount={} -- no RefundRequested will be published, so nothing is "
                        + "refunded. A zero on a REFUND decision normally means post-sales priced it with "
                        + "a fallback policy context (check for 'policy context MISS' in post-sales).",
                    caseId, intentId, envelope.eventId(), amount);
            } else {
                LOGGER.debug("payment no-op for PostSalesApproved case={} eventId={} decisionKind={}: "
                        + "approved refund amount is zero", caseId, envelope.eventId(), decisionKind);
            }
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
        String intentId = paymentCommands.findIntentIdByBusinessRef(payload.requiredText("journeyOrderId")).orElse(null);
        if (intentId == null) {
            throw new AckSkipEventException("no payment intent found for ancillary refund request");
        }
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
