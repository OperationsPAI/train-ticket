package com.trainticket.bookingorchestration.observability;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;

/**
 * The booking saga instruments named by the business metric contract.
 *
 * <p>{@code trackActive} and {@code observeOldestAge} report a level rebuilt from durable saga
 * state, so a process restart cannot reset a real backlog to zero. {@code transition} is an event
 * count recorded where the saga actually changed. Every label value is a closed enum; the failure
 * reasons are owned here rather than copied from a payload, because upstream failure text is
 * unbounded and would otherwise become a series per message.
 */
public interface BookingSagaMetrics {
    /** Rebuilds the number of nonterminal sagas waiting at a stage from durable saga state. */
    void trackActive(Stage stage, long activeSagas);

    /** Rebuilds the age of the oldest nonterminal saga at a stage from durable saga state. */
    void observeOldestAge(Stage stage, Duration oldestAge);

    void transition(Stage stage, Outcome outcome, ReasonCode reasonCode);

    enum Stage {
        RISK_CHECK("risk_check"),
        RESERVATION("reservation"),
        SEAT_ASSIGNMENT("seat_assignment"),
        PAYMENT("payment"),
        TICKETING("ticketing"),
        INVOICING("invoicing"),
        COMPENSATION("compensation"),
        OTHER("other");

        private final String value;

        Stage(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Stage fromValue(String rawValue) {
            return BookingSagaVocabulary.match(values(), Stage::value, rawValue, OTHER);
        }
    }

    enum Outcome {
        STARTED("started"),
        ADVANCED("advanced"),
        ACCEPTED("accepted"),
        REJECTED("rejected"),
        RETRY_SCHEDULED("retry_scheduled"),
        MANUAL_REVIEW("manual_review"),
        COMPLETED("completed"),
        OTHER("other");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Outcome fromValue(String rawValue) {
            return BookingSagaVocabulary.match(values(), Outcome::value, rawValue, OTHER);
        }
    }

    /**
     * The bounded reason a saga left a stage.
     *
     * <p>{@link #OTHER} is the only value an unrecognized upstream reason may become. Callers map
     * their own failure to one of these constants; they never pass the upstream text through.
     */
    enum ReasonCode {
        NONE("none"),
        RISK_BLOCKED("risk_blocked"),
        RISK_CHALLENGE("risk_challenge"),
        RISK_HOLD("risk_hold"),
        CAPACITY_HOLD_FAILED("capacity_hold_failed"),
        PROVIDER_FAILURE("provider_failure"),
        PAYMENT_FAILED("payment_failed"),
        ENTITLEMENT_FAILED("entitlement_failed"),
        STEP_TIMED_OUT("step_timed_out"),
        OTHER("other");

        private final String value;

        ReasonCode(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static ReasonCode fromValue(String rawValue) {
            return BookingSagaVocabulary.match(values(), ReasonCode::value, rawValue, OTHER);
        }
    }

    final class BookingSagaVocabulary {
        private BookingSagaVocabulary() {
        }

        static <T> T match(T[] candidates, Function<T, String> value, String rawValue, T fallback) {
            if (rawValue == null) {
                return fallback;
            }
            String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
            for (T candidate : candidates) {
                if (value.apply(candidate).equals(normalized)) {
                    return candidate;
                }
            }
            return fallback;
        }
    }
}
