package com.trainticket.payment.domain.ports;

import com.trainticket.payment.domain.LatePaymentCase;
import java.util.Optional;

public interface LatePaymentCaseRepository {
    Optional<LatePaymentCase> findById(String latePaymentCaseId);

    void save(LatePaymentCase latePaymentCase);
}
