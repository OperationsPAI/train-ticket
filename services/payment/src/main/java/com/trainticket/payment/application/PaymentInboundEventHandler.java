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

    private void handlePostSalesApproved(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        String caseId = payload.requiredText("caseId");
        String reason = payload.optionalText("reason");
        String intentId = payload.optionalPaymentIntentIdFromApprovedActions();
        if (intentId == null) {
            String orderRef = payload.orderRefFromApprovedActions();
            intentId = orderRef == null ? null : paymentCommands.findIntentIdByBusinessRef(orderRef).orElse(null);
        }
        if (intentId == null) {
            // Approval does not reference any payment this context knows about.
            return;
        }
        Money amount = payload.moneyFromApprovedActions();
        if (amount == null || amount.isZero()) {
            // CHANGE decisions routinely approve with a zero refund amount:
            // nothing to refund, so this event is a no-op for payment.
            return;
        }
        paymentCommands.requestRefund(
            intentId,
            amount,
            reason == null ? "post-sales-approved" : reason,
            caseId,
            caseId,
            envelope.correlationId()
        );
    }
}
