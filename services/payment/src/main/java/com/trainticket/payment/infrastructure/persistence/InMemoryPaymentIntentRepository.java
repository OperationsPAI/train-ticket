package com.trainticket.payment.infrastructure.persistence;

import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.ports.PaymentIntentRepository;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnMissingBean(PaymentIntentRepository.class)
public class InMemoryPaymentIntentRepository implements PaymentIntentRepository {
    private final Map<String, PaymentIntent> intents = new ConcurrentHashMap<>();

    @Override
    public Optional<PaymentIntent> findById(String paymentIntentId) {
        return Optional.ofNullable(intents.get(paymentIntentId));
    }

    @Override
    public Optional<PaymentIntent> findLatestByBusinessRef(String businessRef) {
        return intents.values().stream()
            .filter(intent -> intent.businessRef().equals(businessRef))
            .sorted(Comparator.comparing(PaymentIntent::paymentIntentId).reversed())
            .findFirst();
    }

    @Override
    public void save(PaymentIntent intent) {
        intents.put(intent.paymentIntentId(), intent);
    }
}
