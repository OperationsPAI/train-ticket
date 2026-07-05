package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class InMemoryRevenueRepository implements RevenueRecognitionRepository {
    private final ConcurrentMap<String, RevenueRecognition> recognitions = new ConcurrentHashMap<>();

    @Override
    public Optional<RevenueRecognition> findById(String revenueRecognitionId) {
        return Optional.ofNullable(recognitions.get(revenueRecognitionId));
    }

    @Override
    public void save(RevenueRecognition recognition) {
        recognitions.put(recognition.revenueRecognitionId(), recognition);
    }
}
