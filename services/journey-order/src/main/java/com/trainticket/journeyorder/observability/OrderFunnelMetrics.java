package com.trainticket.journeyorder.observability;

import java.util.Locale;

/**
 * The order funnel transitions this service owns.
 *
 * <p>The funnel is shared with booking-orchestration, which owns the reservation, payment and
 * ticket stages; search and quote belong to offer-management. Every label value is a closed enum
 * so a series can never be named after an order, a request or an exception message. A value the
 * vocabulary does not define collapses to {@code other} instead of being passed through.
 */
public interface OrderFunnelMetrics {
    void transition(Stage stage, Outcome outcome, Channel channel);

    enum Stage {
        SEARCH("search"),
        QUOTE("quote"),
        ORDER("order"),
        RESERVATION("reservation"),
        PAYMENT("payment"),
        TICKET("ticket"),
        OTHER("other");

        private final String value;

        Stage(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Stage fromValue(String rawValue) {
            return OrderFunnelVocabulary.match(values(), Stage::value, rawValue, OTHER);
        }
    }

    enum Outcome {
        ATTEMPTED("attempted"),
        ACCEPTED("accepted"),
        REJECTED("rejected"),
        OTHER("other");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Outcome fromValue(String rawValue) {
            return OrderFunnelVocabulary.match(values(), Outcome::value, rawValue, OTHER);
        }
    }

    /**
     * The purchase channel an order was placed through.
     *
     * <p>The resolved offer carries the channel reference as free text, so it is decoded here
     * rather than copied onto a label.
     */
    enum Channel {
        WEB("web"),
        MOBILE("mobile"),
        COUNTER("counter"),
        PARTNER("partner"),
        API("api"),
        OTHER("other");

        private final String value;

        Channel(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Channel fromRef(String channelRef) {
            return OrderFunnelVocabulary.match(values(), Channel::value, channelRef, OTHER);
        }
    }

    /** Shared lookup so unknown values collapse to the same fallback in every vocabulary. */
    final class OrderFunnelVocabulary {
        private OrderFunnelVocabulary() {
        }

        static <T> T match(T[] candidates, java.util.function.Function<T, String> value, String rawValue, T fallback) {
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
