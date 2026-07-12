package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.SupplierSettlement;
import java.time.LocalDate;
import java.util.Optional;

public interface SupplierSettlementRepository {
    Optional<SupplierSettlement> findById(String supplierSettlementId);
    Optional<SupplierSettlement> findBySupplierAndPeriod(String supplierId, LocalDate startDate, LocalDate endDate);
    void save(SupplierSettlement settlement);
}
