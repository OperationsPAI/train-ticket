package com.trainticket.payment.domain.ports;

import com.trainticket.payment.domain.PaymentIntent;
import java.util.Optional;

public interface PaymentIntentRepository {
    Optional<PaymentIntent> findById(String paymentIntentId);

    Optional<PaymentIntent> findLatestByBusinessRef(String businessRef);

    void save(PaymentIntent intent);
}
