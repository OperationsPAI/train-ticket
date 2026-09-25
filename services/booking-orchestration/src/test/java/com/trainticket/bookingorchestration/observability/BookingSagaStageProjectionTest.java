package com.trainticket.bookingorchestration.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainticket.bookingorchestration.observability.BookingSagaMetrics.Stage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookingSagaStageProjectionTest {
    private static final Instant START = Instant.parse("2026-09-25T00:00:00Z");

    @Test
    void mapsEveryPersistedStatusOntoTheClosedStageVocabulary() {
        assertThat(BookingSagaStageProjection.stageOf("RISK_CHECKING")).isEqualTo(Stage.RISK_CHECK);
        assertThat(BookingSagaStageProjection.stageOf("RESERVING")).isEqualTo(Stage.RESERVATION);
        assertThat(BookingSagaStageProjection.stageOf("CONFIRMING")).isEqualTo(Stage.RESERVATION);
        assertThat(BookingSagaStageProjection.stageOf("SEAT_ASSIGNING")).isEqualTo(Stage.SEAT_ASSIGNMENT);
        assertThat(BookingSagaStageProjection.stageOf("AWAITING_PAYMENT")).isEqualTo(Stage.PAYMENT);
        assertThat(BookingSagaStageProjection.stageOf("TICKETING")).isEqualTo(Stage.TICKETING);
        assertThat(BookingSagaStageProjection.stageOf("INVOICING")).isEqualTo(Stage.INVOICING);
        assertThat(BookingSagaStageProjection.stageOf("COMPENSATING")).isEqualTo(Stage.COMPENSATION);
        assertThat(BookingSagaStageProjection.stageOf("A_STATUS_ADDED_LATER")).isEqualTo(Stage.OTHER);
        assertThat(BookingSagaStageProjection.stageOf(null)).isEqualTo(Stage.OTHER);
    }

    @Test
    void countsSagasPerStageAndReportsTheOldestAge() {
        var rows = List.of(
            new BookingSagaStageRepository.ActiveSaga("saga-1", "AWAITING_PAYMENT", START.plusSeconds(10)),
            new BookingSagaStageRepository.ActiveSaga("saga-2", "AWAITING_PAYMENT", START.plusSeconds(40)),
            new BookingSagaStageRepository.ActiveSaga("saga-3", "RESERVING", START.plusSeconds(5)));

        var levels = BookingSagaStageProjection.project(rows, START.plusSeconds(100));

        assertThat(levels.get(Stage.PAYMENT).activeSagas()).isEqualTo(2);
        assertThat(levels.get(Stage.PAYMENT).oldestAge()).isEqualTo(java.time.Duration.ofSeconds(90));
        assertThat(levels.get(Stage.RESERVATION).activeSagas()).isEqualTo(1);
        assertThat(levels.get(Stage.RESERVATION).oldestAge()).isEqualTo(java.time.Duration.ofSeconds(95));
        // No saga at a stage is a real zero, not a missing sample.
        assertThat(levels.get(Stage.TICKETING).activeSagas()).isZero();
        assertThat(levels.get(Stage.TICKETING).oldestAge()).isZero();
    }

    @Test
    void reRunningOnTheSameRowsIsStable() {
        var rows = List.of(
            new BookingSagaStageRepository.ActiveSaga("saga-1", "TICKETING", START),
            new BookingSagaStageRepository.ActiveSaga("saga-2", "INVOICING", START.plusSeconds(20)));

        assertThat(BookingSagaStageProjection.project(rows, START.plusSeconds(60)))
            .isEqualTo(BookingSagaStageProjection.project(rows, START.plusSeconds(60)));
    }
}
