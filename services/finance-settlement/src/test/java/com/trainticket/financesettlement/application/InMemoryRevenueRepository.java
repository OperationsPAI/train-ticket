package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.util.Comparator;
import java.util.List;
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
    public List<RevenueRecognition> findByOrderId(String orderId) {
        return recognitions.values().stream()
            .filter(recognition -> recognition.orderId().equals(orderId))
            .sorted(Comparator.comparing(RevenueRecognition::recognizedAt).thenComparing(RevenueRecognition::revenueRecognitionId))
            .toList();
    }

    @Override
    public void save(RevenueRecognition recognition) {
        recognitions.put(recognition.revenueRecognitionId(), recognition);
    }
}
