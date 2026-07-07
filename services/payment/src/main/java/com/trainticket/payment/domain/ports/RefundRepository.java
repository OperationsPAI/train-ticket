package com.trainticket.payment.domain.ports;

import com.trainticket.payment.domain.Refund;
import java.util.Optional;

public interface RefundRepository {
    Optional<Refund> findById(String refundId);

    void save(Refund refund);
}
