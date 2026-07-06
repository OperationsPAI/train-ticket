package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.util.List;
import java.util.Optional;

public interface RevenueRecognitionRepository {
    Optional<RevenueRecognition> findById(String revenueRecognitionId);
    List<RevenueRecognition> findByOrderId(String orderId);
    void save(RevenueRecognition recognition);
}
