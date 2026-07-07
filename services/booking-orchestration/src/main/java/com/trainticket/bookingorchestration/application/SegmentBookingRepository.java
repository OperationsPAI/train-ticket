package com.trainticket.bookingorchestration.application;

import com.trainticket.bookingorchestration.domain.SegmentBooking;
import java.util.List;
import java.util.Optional;

public interface SegmentBookingRepository {
    Optional<SegmentBookingRecord> findById(String segmentBookingId);
    Optional<SegmentBookingRecord> findByIdempotencyKey(String idempotencyKey);
    Optional<SegmentBookingRecord> findByCapacityHoldId(String capacityHoldId);
    List<SegmentBookingRecord> findBySagaId(String sagaId);
    void save(SegmentBooking booking, String sagaId);

    record SegmentBookingRecord(SegmentBooking booking, String sagaId) {}
}
