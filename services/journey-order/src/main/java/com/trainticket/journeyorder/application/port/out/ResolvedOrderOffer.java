package com.trainticket.journeyorder.application.port.out;

import com.trainticket.journeyorder.domain.*;
import java.util.List;

public record ResolvedOrderOffer(String accountId, String channelId, OfferSnapshotRef offer,
        List<TravelerRef> travelers, List<SegmentOrderSnapshot> segments, List<OrderItem> items,
        String productCode, List<EligibilityUsage> eligibilityUsages) {
    public ResolvedOrderOffer {
        travelers = List.copyOf(travelers);
        segments = List.copyOf(segments);
        items = List.copyOf(items);
        eligibilityUsages = List.copyOf(eligibilityUsages);
    }

    public record EligibilityUsage(String travelerId, String eligibilityType, String eligibilityCertificateId) {}
}
