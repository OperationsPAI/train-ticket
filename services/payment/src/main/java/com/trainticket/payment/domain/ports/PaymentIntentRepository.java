package com.trainticket.payment.domain.ports;

import com.trainticket.payment.domain.PaymentIntent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentIntentRepository {
    Optional<PaymentIntent> findById(String paymentIntentId);

    Optional<PaymentIntent> findLatestByBusinessRef(String businessRef);

    List<PaymentIntent> findExpiredOpenIntents(Instant now, int limit);

    void save(PaymentIntent intent);
}
