package com.trainticket.bookingorchestration.application;

import java.util.Optional;

public interface PaymentIntentSagaReferenceRepository {
    Optional<String> findSagaId(String paymentIntentId);
    void saveIfAbsent(String paymentIntentId, String sagaId);
}
