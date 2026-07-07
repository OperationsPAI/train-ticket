package com.trainticket.bookingorchestration.application;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnMissingBean(PaymentIntentSagaReferenceRepository.class)
public class InMemoryPaymentIntentSagaReferenceRepository implements PaymentIntentSagaReferenceRepository {
    private final ConcurrentHashMap<String, String> refs = new ConcurrentHashMap<>();
    @Override public Optional<String> findSagaId(String paymentIntentId) { return Optional.ofNullable(refs.get(paymentIntentId)); }
    @Override public void saveIfAbsent(String paymentIntentId, String sagaId) { refs.putIfAbsent(paymentIntentId, sagaId); }
}
