package com.trainticket.postsales.application;

import java.util.Optional;

public interface CompensationMismatchStore {
    Optional<CompensationMismatchCheckpoint> find(String caseId);
    CompensationMismatchCheckpoint record(CompensationMismatchCheckpoint checkpoint);
}
