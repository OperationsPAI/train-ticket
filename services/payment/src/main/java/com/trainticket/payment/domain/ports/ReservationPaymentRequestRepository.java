package com.trainticket.payment.domain.ports;

import com.trainticket.payment.application.ReservationPaymentRequest;
import java.util.Optional;

public interface ReservationPaymentRequestRepository {
    Optional<ReservationPaymentRequest> findBySegmentBookingId(String segmentBookingId);

    ReservationPaymentRequest saveIfAbsent(ReservationPaymentRequest request);
}
