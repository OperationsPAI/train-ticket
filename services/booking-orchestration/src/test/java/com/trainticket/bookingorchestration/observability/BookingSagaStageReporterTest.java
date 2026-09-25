package com.trainticket.bookingorchestration.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainticket.bookingorchestration.observability.BookingSagaMetrics.Outcome;
import com.trainticket.bookingorchestration.observability.BookingSagaMetrics.ReasonCode;
import com.trainticket.bookingorchestration.observability.BookingSagaMetrics.Stage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The reporter derives its levels from the durable repository on every tick, so a saga leaving a
 * stage decrements without any in-process memory of the sagas it never saw.
 */
class BookingSagaStageReporterTest {
    private static final Instant START = Instant.parse("2026-09-25T00:00:00Z");
    private static final Instant NOW = START.plusSeconds(90);

    @Test
    void rebuildsLevelsFromTheRepositoryOnEveryTick() {
        FakeRepository repository = new FakeRepository(List.of(
            active("saga-1", "RESERVING", START),
            active("saga-2", "RESERVING", START.plusSeconds(20))));
        RecordingMetrics metrics = new RecordingMetrics();
        BookingSagaStageReporter reporter = new BookingSagaStageReporter(
            repository, metrics, () -> true, Clock.fixed(NOW, ZoneOffset.UTC));

        reporter.report();
        assertThat(metrics.active).containsEntry(Stage.RESERVATION, 2L);
        assertThat(metrics.oldestAge).containsEntry(Stage.RESERVATION, Duration.ofSeconds(90));

        // One reservation finished and one payment is now waiting; the levels follow the store.
        repository.rows = List.of(
            active("saga-2", "RESERVING", START.plusSeconds(20)),
            active("saga-3", "AWAITING_PAYMENT", START.plusSeconds(60)));
        reporter.report();
        assertThat(metrics.active).containsEntry(Stage.RESERVATION, 1L);
        assertThat(metrics.oldestAge).containsEntry(Stage.RESERVATION, Duration.ofSeconds(70));
        assertThat(metrics.active).containsEntry(Stage.PAYMENT, 1L);
        assertThat(metrics.oldestAge).containsEntry(Stage.PAYMENT, Duration.ofSeconds(30));
    }

    @Test
    void aNonLeaderReportsNothing() {
        RecordingMetrics metrics = new RecordingMetrics();
        BookingSagaStageReporter reporter = new BookingSagaStageReporter(
            new FakeRepository(List.of(active("saga-1", "RESERVING", START))),
            metrics, () -> false, Clock.fixed(NOW, ZoneOffset.UTC));

        reporter.report();

        assertThat(metrics.active).isEmpty();
        assertThat(metrics.oldestAge).isEmpty();
    }

    private static BookingSagaStageRepository.ActiveSaga active(String sagaId, String status, Instant updatedAt) {
        return new BookingSagaStageRepository.ActiveSaga(sagaId, status, updatedAt);
    }

    private static final class FakeRepository implements BookingSagaStageRepository {
        private List<ActiveSaga> rows;

        private FakeRepository(List<ActiveSaga> rows) {
            this.rows = rows;
        }

        @Override
        public List<ActiveSaga> findNonterminalSagas() {
            return rows;
        }
    }

    private static final class RecordingMetrics implements BookingSagaMetrics {
        private final Map<Stage, Long> active = new LinkedHashMap<>();
        private final Map<Stage, Duration> oldestAge = new LinkedHashMap<>();
        private final List<String> transitions = new ArrayList<>();

        @Override
        public void trackActive(Stage stage, long activeSagas) {
            active.put(stage, activeSagas);
        }

        @Override
        public void observeOldestAge(Stage stage, Duration oldest) {
            oldestAge.put(stage, oldest);
        }

        @Override
        public void transition(Stage stage, Outcome outcome, ReasonCode reasonCode) {
            transitions.add(stage.value() + "/" + outcome.value() + "/" + reasonCode.value());
        }
    }
}
