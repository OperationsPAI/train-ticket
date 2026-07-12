package com.trainticket.financesettlement.application;

import java.util.Optional;

public interface PaymentIntentOrderReferenceRepository {
    void save(String paymentIntentId, String orderReference);
    Optional<String> findOrderReference(String paymentIntentId);
}
