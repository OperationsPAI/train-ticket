package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.SupplierSettlement;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemorySupplierSettlementRepository implements SupplierSettlementRepository {
    private final ConcurrentMap<String, SupplierSettlement> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> bySupplierPeriod = new ConcurrentHashMap<>();

    @Override
    public Optional<SupplierSettlement> findById(String supplierSettlementId) {
        return Optional.ofNullable(byId.get(supplierSettlementId));
    }

    @Override
    public Optional<SupplierSettlement> findBySupplierAndPeriod(String supplierId, LocalDate startDate, LocalDate endDate) {
        return Optional.ofNullable(bySupplierPeriod.get(key(supplierId, startDate, endDate))).map(byId::get);
    }

    @Override
    public void save(SupplierSettlement settlement) {
        byId.put(settlement.supplierSettlementId(), settlement);
        bySupplierPeriod.put(key(settlement.supplierId(), settlement.period().startDate(), settlement.period().endDate()), settlement.supplierSettlementId());
    }

    private static String key(String supplierId, LocalDate startDate, LocalDate endDate) {
        return supplierId + "|" + startDate + "|" + endDate;
    }
}
