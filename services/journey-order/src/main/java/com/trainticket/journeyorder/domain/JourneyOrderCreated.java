package com.trainticket.journeyorder.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record JourneyOrderCreated(
    EventEnvelope envelope,
    String orderId,
    String accountId,
    String offerId,
    MonetarySummary monetarySummary,
    java.util.List<TravelerRef> travelerRefs,
    java.util.List<String> segmentRefs,
    java.time.Instant createdAt
) implements JourneyOrderEvent {
    public JourneyOrderCreated {
        travelerRefs = java.util.List.copyOf(travelerRefs);
        segmentRefs = java.util.List.copyOf(segmentRefs);
    }
}
