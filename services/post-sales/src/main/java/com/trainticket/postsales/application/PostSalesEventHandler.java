package com.trainticket.postsales.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PostSalesEventHandler {
    private final ConsumedEventLog consumedEventLog;
    private final PostSalesApplicationService applicationService;

    public PostSalesEventHandler(ConsumedEventLog consumedEventLog, PostSalesApplicationService applicationService) {
        this.consumedEventLog = consumedEventLog;
        this.applicationService = applicationService;
    }

    public EventSubscriber.HandlerResult handle(EventEnvelope envelope) {
        if (!consumedEventLog.recordIfFirstSeen(envelope.eventId())) {
            return EventSubscriber.HandlerResult.SUCCESS;
        }
        if ("CapacityReleased".equals(envelope.eventType())) {
            String segmentBookingRef = segmentBookingRef(envelope.payload());
            if (segmentBookingRef != null) {
                applicationService.applyForSegmentBooking(segmentBookingRef, envelope.eventId(), envelope.correlationId());
            }
        }
        return EventSubscriber.HandlerResult.SUCCESS;
    }

    private static String segmentBookingRef(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return null;
        }
        Object direct = map.get("segmentBookingId");
        if (direct instanceof String text && !text.isBlank()) {
            return text;
        }
        Object references = map.get("references");
        if (references instanceof Map<?, ?> refs) {
            Object nested = refs.get("segmentBookingRef");
            if (nested instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }
}
