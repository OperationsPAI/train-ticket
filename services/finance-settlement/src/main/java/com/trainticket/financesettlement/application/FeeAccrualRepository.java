package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.FeeAccrual;
import java.util.List;
import java.util.Optional;

public interface FeeAccrualRepository {
    Optional<FeeAccrual> findById(String feeAccrualId);
    List<FeeAccrual> findByOrderId(String orderId);
    void save(FeeAccrual accrual);
}
