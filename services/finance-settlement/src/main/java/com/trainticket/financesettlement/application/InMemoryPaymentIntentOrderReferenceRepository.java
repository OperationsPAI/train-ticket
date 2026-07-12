package com.trainticket.financesettlement.application;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryPaymentIntentOrderReferenceRepository implements PaymentIntentOrderReferenceRepository {
    private final ConcurrentMap<String, String> orderReferencesByPaymentIntentId = new ConcurrentHashMap<>();

    @Override
    public void save(String paymentIntentId, String orderReference) {
        orderReferencesByPaymentIntentId.put(requireText(paymentIntentId, "paymentIntentId"), requireText(orderReference, "orderReference"));
    }

    @Override
    public Optional<String> findOrderReference(String paymentIntentId) {
        return Optional.ofNullable(orderReferencesByPaymentIntentId.get(paymentIntentId));
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
