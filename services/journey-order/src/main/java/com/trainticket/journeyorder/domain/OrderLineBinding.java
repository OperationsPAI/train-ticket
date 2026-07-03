package com.trainticket.journeyorder.domain;

import java.util.Objects;
import java.util.Set;

public record OrderLineBinding(
    String orderItemId,
    String travelerId,
    String segmentRef,
    String ancillaryRef
) {
    public OrderLineBinding {
        requireText(orderItemId, "orderItemId");
        requireText(travelerId, "travelerId");
        if ((segmentRef == null || segmentRef.isBlank()) && (ancillaryRef == null || ancillaryRef.isBlank())) {
            throw new DomainRuleViolation("order line binding must reference a segment or ancillary service");
        }
        segmentRef = segmentRef == null ? "" : segmentRef;
        ancillaryRef = ancillaryRef == null ? "" : ancillaryRef;
    }

    public void requireKnownRefs(Set<String> knownTravelerIds, Set<String> knownSegmentRefs) {
        if (!knownTravelerIds.contains(travelerId)) {
            throw new DomainRuleViolation("binding references unknown traveler " + travelerId);
        }
        if (!segmentRef.isBlank() && !knownSegmentRefs.contains(segmentRef)) {
            throw new DomainRuleViolation("binding references unknown segment " + segmentRef);
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
    }
}
