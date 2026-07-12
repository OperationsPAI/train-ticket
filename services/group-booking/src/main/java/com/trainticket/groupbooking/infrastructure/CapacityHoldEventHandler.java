package com.trainticket.groupbooking.infrastructure;

import com.trainticket.groupbooking.application.GroupBookingService;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.HandlerResult;
import org.springframework.stereotype.Component;

@Component
public class CapacityHoldEventHandler {
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
            return HandlerResult.FATAL_FAILURE;
        } catch (RuntimeException exception) {
            return HandlerResult.TRANSIENT_FAILURE;
        }
    }
}
