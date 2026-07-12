package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RevenueRecognitionRepository {
    Optional<RevenueRecognition> findById(String revenueRecognitionId);
    List<RevenueRecognition> findByOrderId(String orderId);
    default List<RevenueRecognition> findBySupplierAndPeriod(String supplierId, LocalDate startDate, LocalDate endDate) { return List.of(); }
    void save(RevenueRecognition recognition);
}
