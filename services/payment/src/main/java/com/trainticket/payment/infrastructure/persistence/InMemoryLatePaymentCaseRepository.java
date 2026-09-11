package com.trainticket.payment.infrastructure.persistence;

import com.trainticket.payment.domain.LatePaymentCase;
import com.trainticket.payment.domain.ports.LatePaymentCaseRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnMissingBean(LatePaymentCaseRepository.class)
public class InMemoryLatePaymentCaseRepository implements LatePaymentCaseRepository {
    private final Map<String, LatePaymentCase> cases = new ConcurrentHashMap<>();

    @Override
    public Optional<LatePaymentCase> findById(String latePaymentCaseId) {
        return Optional.ofNullable(cases.get(latePaymentCaseId));
    }

    @Override
    public void save(LatePaymentCase latePaymentCase) {
        cases.put(latePaymentCase.latePaymentCaseId(), latePaymentCase);
    }

    /**
     * Enumerates every open case. Deliberately NOT on
     * {@link LatePaymentCaseRepository}: the application only ever resolves a case by its folded
     * id, so an unbounded scan has no production caller and would invite one. This exists so a
     * test can assert that one late collection opened exactly one case.
     */
    public List<LatePaymentCase> findAll() {
        return List.copyOf(cases.values());
    }
}
