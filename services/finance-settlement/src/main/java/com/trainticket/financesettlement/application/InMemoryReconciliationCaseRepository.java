package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.ReconciliationCase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryReconciliationCaseRepository implements ReconciliationCaseRepository {
    private final ConcurrentMap<String, ReconciliationCase> reconciliationCases = new ConcurrentHashMap<>();

    @Override
    public Optional<ReconciliationCase> findById(String reconciliationCaseId) {
        return Optional.ofNullable(reconciliationCases.get(reconciliationCaseId));
    }

    @Override
    public List<ReconciliationCase> find(String orderId, int limit, int offset) {
        return filtered(orderId).stream().skip(offset).limit(limit).toList();
    }

    @Override
    public long count(String orderId) {
        return filtered(orderId).size();
    }

    @Override
    public void save(ReconciliationCase reconciliationCase) {
        reconciliationCases.put(reconciliationCase.reconciliationCaseId(), reconciliationCase);
    }

    private List<ReconciliationCase> filtered(String orderId) {
        return reconciliationCases.values().stream()
            .filter(item -> orderId == null || orderId.isBlank() || item.orderId().equals(orderId))
            .sorted(Comparator.comparing(ReconciliationCase::openedAt).thenComparing(ReconciliationCase::reconciliationCaseId))
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
}
