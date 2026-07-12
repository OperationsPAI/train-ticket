package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.ReconciliationBatch;
import java.time.LocalDate;
import java.util.Optional;

public interface ReconciliationBatchRepository {
    Optional<ReconciliationBatch> findById(String batchId);
    Optional<ReconciliationBatch> findBySettlementDate(LocalDate settlementDate);
    void save(ReconciliationBatch batch);
}
