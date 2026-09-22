package com.trainticket.payment.domain.ports;

import com.trainticket.payment.domain.Refund;
import java.util.List;
import java.util.Optional;

public interface RefundRepository {
    Optional<Refund> findById(String refundId);
    List<Refund> findByBusinessCaseRef(String businessCaseRef);

    void save(Refund refund);
}
