package com.trainticket.platformkit.observability;

import java.time.Duration;

/** Low-cardinality health signals emitted by the transactional outbox relay. */
public interface OutboxMetrics {
    enum FailureStage {
        SELECT("select"),
        PUBLISH("publish"),
        MARK_PUBLISHED("mark_published");

        private final String value;

        FailureStage(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /**
     * Records the bounded head-of-line sample returned by the relay query.
     *
     * <p>The sample is not presented as a full-table count: it is capped by the relay batch size.
     * A value equal to that cap means at least one full batch is waiting. This avoids an exact
     * {@code count(*)} scan becoming a new source of database pressure during a large backlog.
     */
    void observeUnpublishedSample(int rows, Duration oldestAge);

    /** Records rows whose bounded Redis pipeline completed successfully. */
    void recordPublished(int rows);

    void recordFailure(FailureStage stage);
}
