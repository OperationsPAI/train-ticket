package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.ReconciliationBatch;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryReconciliationBatchRepository implements ReconciliationBatchRepository {
    private final ConcurrentMap<String, ReconciliationBatch> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<LocalDate, String> byDate = new ConcurrentHashMap<>();

    @Override
    public Optional<ReconciliationBatch> findById(String batchId) {
        return Optional.ofNullable(byId.get(batchId));
    }

    @Override
    public Optional<ReconciliationBatch> findBySettlementDate(LocalDate settlementDate) {
        return Optional.ofNullable(byDate.get(settlementDate)).map(byId::get);
    }

    @Override
    public void save(ReconciliationBatch batch) {
        byId.put(batch.batchId(), batch);
        byDate.put(batch.settlementDate(), batch.batchId());
    }
}
