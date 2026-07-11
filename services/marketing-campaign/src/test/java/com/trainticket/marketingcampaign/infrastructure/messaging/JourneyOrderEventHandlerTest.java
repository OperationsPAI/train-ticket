package com.trainticket.marketingcampaign.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.HandlerResult;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JourneyOrderEventHandlerTest {
    private final JourneyOrderEventHandler handler = new JourneyOrderEventHandler();

    @Test
    void journeyOrderConfirmedReturnsSuccess() {
        EventEnvelope envelope = envelope("JourneyOrderConfirmed",
            Map.of("orderId", "jo-123", "accountId", "acc-456"));

        HandlerResult result = handler.handle(envelope);

        assertThat(result).isEqualTo(HandlerResult.SUCCESS);
    }

    @Test
    void unknownEventTypeReturnsSuccess() {
        EventEnvelope envelope = envelope("SomeOtherEvent", Map.of("key", "value"));

        HandlerResult result = handler.handle(envelope);

        assertThat(result).isEqualTo(HandlerResult.SUCCESS);
    }

    @Test
    void journeyOrderConfirmedWithNonMapPayloadReturnsSuccess() {
        EventEnvelope envelope = envelope("JourneyOrderConfirmed", "plain-string-payload");

        HandlerResult result = handler.handle(envelope);

        assertThat(result).isEqualTo(HandlerResult.SUCCESS);
    }

    private static EventEnvelope envelope(String eventType, Object payload) {
        return new EventEnvelope(
            PrefixedIds.newEventId(),
            eventType,
            Instant.now(),
            PrefixedIds.newCorrelationId(),
            null,
            "journey-order",
            1,
            payload
        );
    }
}
