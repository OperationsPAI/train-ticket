package com.trainticket.marketingcampaign.infrastructure.messaging;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.HandlerResult;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles events from the journey-order stream relevant to campaign analytics.
 * Currently logs JourneyOrderConfirmed; campaign-matching logic is deferred.
 */
public class JourneyOrderEventHandler {
    private static final Logger LOG = LoggerFactory.getLogger(JourneyOrderEventHandler.class);

    public HandlerResult handle(EventEnvelope envelope) {
        if ("JourneyOrderConfirmed".equals(envelope.eventType())) {
            LOG.info("JourneyOrderConfirmed received eventId={} correlationId={} orderId={}",
                envelope.eventId(), envelope.correlationId(), extractField(envelope, "orderId"));
            return HandlerResult.SUCCESS;
        }
        LOG.debug("Ignoring event type={} eventId={}", envelope.eventType(), envelope.eventId());
        return HandlerResult.SUCCESS;
    }

    private static String extractField(EventEnvelope envelope, String field) {
        if (envelope.payload() instanceof Map<?, ?> map) {
            Object value = map.get(field);
            return value != null ? value.toString() : "unknown";
        }
        return "unknown";
    }
}
