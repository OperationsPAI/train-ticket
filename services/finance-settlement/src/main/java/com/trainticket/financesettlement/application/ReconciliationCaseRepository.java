package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.ReconciliationCase;
import java.util.List;
import java.util.Optional;

public interface ReconciliationCaseRepository {
    Optional<ReconciliationCase> findById(String reconciliationCaseId);
    List<ReconciliationCase> find(String orderId, int limit, int offset);
    long count(String orderId);
    void save(ReconciliationCase reconciliationCase);
}
