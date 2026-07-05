package com.trainticket.payment.application;

import com.trainticket.payment.domain.DomainRuleViolation;
import com.trainticket.payment.domain.Money;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PaymentInboundEventHandler implements EventSubscriber.EventHandler {
    private final ConsumedEventDeduplicator deduplicator;
    private final PaymentCommandService paymentCommands;

    public PaymentInboundEventHandler(ConsumedEventDeduplicator deduplicator, PaymentCommandService paymentCommands) {
        this.deduplicator = Objects.requireNonNull(deduplicator, "deduplicator is required");
        this.paymentCommands = Objects.requireNonNull(paymentCommands, "paymentCommands are required");
    }

    @Override
    public HandlerResult handle(EventEnvelope envelope) {
        if (deduplicator.isProcessed(envelope.eventId())) {
            return HandlerResult.SUCCESS;
        }
        try {
            dispatch(envelope);
            deduplicator.recordProcessed(envelope.eventId());
            return HandlerResult.SUCCESS;
        } catch (DomainRuleViolation | IllegalArgumentException exception) {
            return HandlerResult.FATAL_FAILURE;
        } catch (RuntimeException exception) {
            return HandlerResult.TRANSIENT_FAILURE;
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
        Money amount = payload.requiredMoney("amount");
        String segmentBookingId = payload.requiredText("segmentBookingId");
        String journeyOrderId = payload.requiredText("journeyOrderId");
        String travelerRef = payload.requiredText("travelerRef");
        String idempotencyKey = payload.requiredText("idempotencyKey");
        String paymentIntentId = paymentCommands.createIntent(journeyOrderId, "purchase", amount, travelerRef, idempotencyKey, envelope.correlationId()).paymentIntentId();
        paymentCommands.authorizeIntent(paymentIntentId, idempotencyKey + ":authorize", envelope.correlationId(), segmentBookingId);
    }

    private void handlePostSalesApproved(EventEnvelope envelope) {
        InboundEventPayload payload = InboundEventPayload.from(envelope);
        String caseId = payload.requiredText("caseId");
        String reason = payload.optionalText("reason");
        paymentCommands.requestRefund(
            payload.paymentIntentIdFromApprovedActions(),
            payload.moneyFromApprovedActions(),
            reason == null ? "post-sales-approved" : reason,
            caseId,
            caseId,
            envelope.correlationId()
        );
    }
}
