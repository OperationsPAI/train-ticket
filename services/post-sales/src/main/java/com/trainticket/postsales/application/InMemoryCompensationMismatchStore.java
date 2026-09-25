package com.trainticket.postsales.application;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCompensationMismatchStore implements CompensationMismatchStore {
    private final ConcurrentHashMap<String, CompensationMismatchCheckpoint> checkpoints = new ConcurrentHashMap<>();

    @Override public Optional<CompensationMismatchCheckpoint> find(String caseId) {
        return Optional.ofNullable(checkpoints.get(PostSalesMapper.stripCasePrefix(caseId)));
    }

    @Override public CompensationMismatchCheckpoint record(CompensationMismatchCheckpoint checkpoint) {
        return checkpoints.compute(checkpoint.caseId(), (ignored, existing) -> existing == null ? checkpoint : existing);
    }
}
