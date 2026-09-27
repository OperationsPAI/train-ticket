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
    java.util.List<SegmentOrderSnapshot> segments,
    java.time.Instant createdAt,
    String sourceIp,
    /**
     * The distance the accepted offer was priced from, when its quote carried
     * one. The revenue report divides captured money by the distance it records,
     * and this event is where the report reads the order from.
     */
    Double distanceKm
) implements JourneyOrderEvent {
    public JourneyOrderCreated {
        travelerRefs = java.util.List.copyOf(travelerRefs);
        segmentRefs = java.util.List.copyOf(segmentRefs);
        segments = java.util.List.copyOf(segments);
    }
}
