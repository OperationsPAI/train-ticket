package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryRevenueRecognitionRepository implements RevenueRecognitionRepository {
    private final ConcurrentMap<String, RevenueRecognition> revenueRecognitions = new ConcurrentHashMap<>();

    @Override
    public Optional<RevenueRecognition> findById(String revenueRecognitionId) {
        return Optional.ofNullable(revenueRecognitions.get(revenueRecognitionId));
    }

    @Override
    public void save(RevenueRecognition recognition) {
        revenueRecognitions.put(recognition.revenueRecognitionId(), recognition);
    }
}
