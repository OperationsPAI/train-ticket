package com.trainticket.bookingorchestration.observability;

import com.trainticket.bookingorchestration.observability.BookingSagaMetrics.Stage;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the durable nonterminal saga rows into the two levels the contract asks for, with no
 * in-process state of its own. Re-running it on the same stored rows after a restart returns the
 * same answer, which is what makes the reported level a property of the database rather than of the
 * process that happened to observe a transition.
 */
public final class BookingSagaStageProjection {
    private BookingSagaStageProjection() {
    }

    public static Map<Stage, StageLevel> project(List<BookingSagaStageRepository.ActiveSaga> sagas, Instant now) {
        long[] activeSagas = new long[Stage.values().length];
        Instant[] oldest = new Instant[Stage.values().length];
        for (BookingSagaStageRepository.ActiveSaga saga : sagas) {
            Stage stage = stageOf(saga.status());
            activeSagas[stage.ordinal()]++;
            Instant current = oldest[stage.ordinal()];
            if (current == null || saga.updatedAt().isBefore(current)) {
                oldest[stage.ordinal()] = saga.updatedAt();
            }
        }
        Map<Stage, StageLevel> levels = new EnumMap<>(Stage.class);
        for (Stage stage : Stage.values()) {
            Instant oldestForStage = oldest[stage.ordinal()];
            Duration age = oldestForStage == null ? Duration.ZERO : Duration.between(oldestForStage, now);
            levels.put(stage, new StageLevel(activeSagas[stage.ordinal()], age.isNegative() ? Duration.ZERO : age));
        }
        return levels;
    }

    /**
     * Maps a persisted saga status onto a stage of the contract vocabulary.
     *
     * <p>A status this build does not know collapses to {@code other}, so a rollout that adds one
     * cannot invent a new label value.
     */
    public static Stage stageOf(String status) {
        if (status == null) {
            return Stage.OTHER;
        }
        return switch (status.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "PLANNED", "RISK_CHECKING" -> Stage.RISK_CHECK;
            // The change flow parks a saga in CONFIRMING while it waits for its replacement
            // reservation, so it is spending that time in the reservation stage.
            case "RESERVING", "CONFIRMING" -> Stage.RESERVATION;
            case "SEAT_ASSIGNING" -> Stage.SEAT_ASSIGNMENT;
            case "AWAITING_PAYMENT" -> Stage.PAYMENT;
            case "TICKETING" -> Stage.TICKETING;
            case "INVOICING" -> Stage.INVOICING;
            case "COMPENSATING" -> Stage.COMPENSATION;
            default -> Stage.OTHER;
        };
    }

    public record StageLevel(long activeSagas, Duration oldestAge) {
    }
}
