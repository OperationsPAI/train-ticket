package com.trainticket.payment.adapters.messaging;

import com.trainticket.payment.application.EventSubscriber;
import com.trainticket.payment.application.HandlerResult;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.util.Objects;

public final class PaymentEventFilter implements EventSubscriber.EventHandler {
    private final EventSubscriber.EventHandler delegate;

    public PaymentEventFilter(EventSubscriber.EventHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    public static boolean handles(EventEnvelope envelope) {
        return switch (envelope.producer()) {
            case "booking-orchestration" -> "SegmentReservationRequested".equals(envelope.eventType());
            case "post-sales" -> "PostSalesApproved".equals(envelope.eventType());
            case "ancillary-service" -> "AncillaryOrderItemRefundPending".equals(envelope.eventType());
            case "payment-channel" -> switch (envelope.eventType()) {
                case "ChannelOrderSucceeded", "ChannelOrderRecoveryDetected", "ChannelOrderFailed", "ChannelOrderMissed",
                     "ChannelRefundSucceeded", "ChannelRefundRecoveryDetected", "ChannelRefundFailed", "ChannelRefundMissed" -> true;
                default -> false;
            };
            default -> false;
        };
    }

    @Override
    public HandlerResult handle(EventEnvelope envelope) {
        return handles(envelope) ? delegate.handle(envelope) : HandlerResult.SUCCESS;
    }
}
