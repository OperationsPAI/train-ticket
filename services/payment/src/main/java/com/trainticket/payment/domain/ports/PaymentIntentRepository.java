package com.trainticket.payment.domain.ports;

import com.trainticket.payment.domain.PaymentIntent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentIntentRepository {
    Optional<PaymentIntent> findById(String paymentIntentId);
    Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentIntent> findLatestByBusinessRef(String businessRef);

    List<PaymentIntent> findExpiredOpenIntents(Instant now, int limit);

    /**
     * The expiry of the oldest intent the sweep still owes, if any.
     *
     * <p>The sweep's own backlog: every intent past its expiry is one the
     * timeout sweep has not expired yet, and the oldest of them is how long the
     * queue has been waiting.
     */
    default java.util.Optional<Instant> findOldestDueExpiry(Instant now) {
        return java.util.Optional.empty();
    }

    void save(PaymentIntent intent);
}
