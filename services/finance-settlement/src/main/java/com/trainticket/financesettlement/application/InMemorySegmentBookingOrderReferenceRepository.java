package com.trainticket.financesettlement.application;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemorySegmentBookingOrderReferenceRepository implements SegmentBookingOrderReferenceRepository {
    private final ConcurrentMap<String, String> orderReferencesBySegmentBookingId = new ConcurrentHashMap<>();

    @Override
    public void save(String segmentBookingId, String orderReference) {
        orderReferencesBySegmentBookingId.put(requireText(segmentBookingId, "segmentBookingId"), requireText(orderReference, "orderReference"));
    }

    @Override
    public Optional<String> findOrderReference(String segmentBookingId) {
        return Optional.ofNullable(orderReferencesBySegmentBookingId.get(segmentBookingId));
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
