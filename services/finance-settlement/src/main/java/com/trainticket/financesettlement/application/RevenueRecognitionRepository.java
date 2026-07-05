package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.util.Optional;

public interface RevenueRecognitionRepository {
    Optional<RevenueRecognition> findById(String revenueRecognitionId);
    void save(RevenueRecognition recognition);
}
