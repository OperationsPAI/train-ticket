package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.ReconciliationCase;
import java.util.List;
import java.util.Optional;

final class InMemoryReconciliationRepository implements ReconciliationCaseRepository {
    @Override
    public Optional<ReconciliationCase> findById(String reconciliationCaseId) {
        return Optional.empty();
    }

    @Override
    public List<ReconciliationCase> find(String orderId, int limit, int offset) {
        return List.of();
    }

    @Override
    public long count(String orderId) {
        return 0;
    }

    @Override
    public void save(ReconciliationCase reconciliationCase) {
    }
}
