package com.trainticket.bookingorchestration.application;

import com.trainticket.bookingorchestration.domain.SegmentBooking;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnMissingBean(SegmentBookingRepository.class)
public class InMemorySegmentBookingRepository implements SegmentBookingRepository {
    private final ConcurrentHashMap<String, SegmentBookingRecord> bookings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotency = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> holds = new ConcurrentHashMap<>();

    @Override
    public Optional<SegmentBookingRecord> findById(String segmentBookingId) {
        return Optional.ofNullable(bookings.get(segmentBookingId));
    }

    @Override
    public Optional<SegmentBookingRecord> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(idempotency.get(idempotencyKey)).map(bookings::get);
    }

    @Override
    public Optional<SegmentBookingRecord> findByCapacityHoldId(String capacityHoldId) {
        return Optional.ofNullable(holds.get(capacityHoldId)).map(bookings::get);
    }

    @Override
    public List<SegmentBookingRecord> findBySagaId(String sagaId) {
        return bookings.values().stream()
            .filter(record -> sagaId.equals(record.sagaId()))
            .toList();
    }

    @Override
    public void save(SegmentBooking booking, String sagaId) {
        bookings.put(booking.segmentBookingId(), new SegmentBookingRecord(booking, sagaId));
        idempotency.putIfAbsent(booking.idempotencyKey(), booking.segmentBookingId());
        booking.capacityHoldId().ifPresent(holdId -> holds.putIfAbsent(holdId, booking.segmentBookingId()));
    }
}
