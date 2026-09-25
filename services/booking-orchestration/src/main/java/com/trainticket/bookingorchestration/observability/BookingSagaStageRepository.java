package com.trainticket.bookingorchestration.observability;

import java.time.Instant;
import java.util.List;

/**
 * A durable, read-only view of the nonterminal sagas used to rebuild the active and oldest-age
 * levels.
 *
 * <p>It reads the saga snapshots the step persistence already writes, so the levels survive a
 * restart of any single process. It is deliberately narrow: identifiers are returned only for the
 * in-process projection and are never used as metric labels.
 */
public interface BookingSagaStageRepository {
    List<ActiveSaga> findNonterminalSagas();

    record ActiveSaga(String sagaId, String status, Instant updatedAt) {
        public ActiveSaga {
            if (sagaId == null || sagaId.isBlank()) {
                throw new IllegalArgumentException("sagaId is required");
            }
            if (status == null || status.isBlank()) {
                throw new IllegalArgumentException("status is required");
            }
            if (updatedAt == null) {
                throw new IllegalArgumentException("updatedAt is required");
            }
        }
    }
}
