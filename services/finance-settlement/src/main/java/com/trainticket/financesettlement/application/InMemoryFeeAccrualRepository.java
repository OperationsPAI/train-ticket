package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.FeeAccrual;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryFeeAccrualRepository implements FeeAccrualRepository {
    private final ConcurrentMap<String, FeeAccrual> accruals = new ConcurrentHashMap<>();

    @Override
    public Optional<FeeAccrual> findById(String feeAccrualId) {
        return Optional.ofNullable(accruals.get(feeAccrualId));
    }

    @Override
    public List<FeeAccrual> findByOrderId(String orderId) {
        return accruals.values().stream()
            .filter(accrual -> accrual.orderId().equals(orderId))
            .sorted(Comparator.comparing(FeeAccrual::feeAccrualId))
            .toList();
    }

    @Override
    public void save(FeeAccrual accrual) {
        accruals.put(accrual.feeAccrualId(), accrual);
    }
}
