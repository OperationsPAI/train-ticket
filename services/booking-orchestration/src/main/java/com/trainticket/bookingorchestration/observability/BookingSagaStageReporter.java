package com.trainticket.bookingorchestration.observability;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the active and oldest-age levels from durable saga state on a fixed period.
 *
 * <p>The levels describe the whole database, so exactly one instance may report them; otherwise the
 * recording rule that sums across replicas would multiply a real backlog. {@link
 * SagaStageReporterLeadership} elects a single reporter with a PostgreSQL advisory lock and fails
 * over on its own when that instance dies.
 */
@Component
@ConditionalOnBean(BookingSagaStageRepository.class)
public final class BookingSagaStageReporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(BookingSagaStageReporter.class);

    private final BookingSagaStageRepository sagas;
    private final BookingSagaMetrics metrics;
    private final BooleanSupplier isLeader;
    private final Clock clock;

    @Autowired
    public BookingSagaStageReporter(BookingSagaStageRepository sagas, BookingSagaMetrics metrics,
                                    SagaStageReporterLeadership leadership, Clock clock) {
        this(sagas, metrics, leadership::isLeader, clock);
    }

    BookingSagaStageReporter(BookingSagaStageRepository sagas, BookingSagaMetrics metrics,
                             BooleanSupplier isLeader, Clock clock) {
        this.sagas = sagas;
        this.metrics = metrics;
        this.isLeader = isLeader;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${booking-orchestration.saga-stage-reporter-delay-ms:30000}")
    public void report() {
        if (!isLeader.getAsBoolean()) {
            return;
        }
        try {
            Instant now = clock.instant();
            Map<BookingSagaMetrics.Stage, BookingSagaStageProjection.StageLevel> levels =
                BookingSagaStageProjection.project(sagas.findNonterminalSagas(), now);
            for (Map.Entry<BookingSagaMetrics.Stage, BookingSagaStageProjection.StageLevel> entry : levels.entrySet()) {
                metrics.trackActive(entry.getKey(), entry.getValue().activeSagas());
                metrics.observeOldestAge(entry.getKey(), entry.getValue().oldestAge());
            }
        } catch (RuntimeException failure) {
            // The next tick retries; a failed read must not be reported as an empty backlog.
            LOGGER.warn("Booking saga stage report skipped: {}", failure.getMessage());
        }
    }
}
