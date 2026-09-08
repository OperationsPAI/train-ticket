package com.trainticket.groupbooking.infrastructure;

import com.trainticket.groupbooking.application.GroupBookingService;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.HandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CapacityHoldEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CapacityHoldEventHandler.class);

    private final GroupBookingService service;

    public CapacityHoldEventHandler(GroupBookingService service) {
        this.service = service;
    }

    public HandlerResult handle(EventEnvelope envelope) {
        if (!"CapacityHoldConfirmed".equals(envelope.eventType())) {
            return HandlerResult.SUCCESS;
        }
        try {
            service.handleCapacityHoldConfirmed(envelope);
            return HandlerResult.SUCCESS;
        } catch (IllegalArgumentException exception) {
            // FATAL_FAILURE dead-letters the event, so the capacity confirmation is
            // never applied to the group booking and no retry will fix it. Silent,
            // that leaves a group stuck with an unconfirmed hold and nothing to
            // explain it.
            LOGGER.error("group-booking REJECTING CapacityHoldConfirmed eventId={} producer={} as "
                    + "unprocessable -- dead-lettering it, so this hold is never applied.",
                envelope.eventId(), envelope.producer(), exception);
            return HandlerResult.FATAL_FAILURE;
        } catch (RuntimeException exception) {
            LOGGER.error("group-booking handler FAILED on CapacityHoldConfirmed eventId={} producer={} "
                    + "-- returning TRANSIENT_FAILURE, so this message stays pending and will be "
                    + "redelivered. If this repeats for the same eventId the consumer is stuck.",
                envelope.eventId(), envelope.producer(), exception);
            return HandlerResult.TRANSIENT_FAILURE;
        }
    }
}
