package com.trainticket.financesettlement.application;

import java.util.Optional;

public interface SegmentBookingOrderReferenceRepository {
    void save(String segmentBookingId, String orderReference);
    Optional<String> findOrderReference(String segmentBookingId);
}
