package com.trainticket.payment.infrastructure.persistence;

import com.trainticket.payment.application.ReservationPaymentRequest;
import com.trainticket.payment.domain.ports.ReservationPaymentRequestRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnMissingBean(ReservationPaymentRequestRepository.class)
public class InMemoryReservationPaymentRequestRepository implements ReservationPaymentRequestRepository {
    private final Map<String, ReservationPaymentRequest> requests = new ConcurrentHashMap<>();

    @Override
    public Optional<ReservationPaymentRequest> findBySegmentBookingId(String segmentBookingId) {
        return Optional.ofNullable(requests.get(segmentBookingId));
    }

    @Override
    public ReservationPaymentRequest saveIfAbsent(ReservationPaymentRequest request) {
        requests.putIfAbsent(request.segmentBookingId(), request);
        return requests.get(request.segmentBookingId());
    }
}
