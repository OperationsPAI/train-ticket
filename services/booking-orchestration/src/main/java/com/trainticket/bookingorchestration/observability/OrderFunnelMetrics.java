package com.trainticket.bookingorchestration.observability;

import java.util.Locale;
import java.util.function.Function;

/**
 * The order funnel transitions this service owns.
 *
 * <p>Journey Order owns the order stage and, with offer-management, quote and search. This service
 * owns reservation, payment and ticket because the booking saga is where those stages are driven.
 * The vocabulary is shared so both services publish the same series; every value is a closed enum
 * and anything the vocabulary does not define collapses to {@code other} rather than being copied
 * from a payload, an order id or an exception message.
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
            return match(values(), Stage::value, rawValue, OTHER);
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
            return match(values(), Outcome::value, rawValue, OTHER);
        }
    }

    /**
     * The purchase channel an order was placed through.
     *
     * <p>This service is downstream of the channel decision and never receives the channel
     * reference, so its transitions report {@link #OTHER}. The vocabulary is still defined here so
     * a future propagation cannot invent a new label value on one side only.
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
            return match(values(), Channel::value, channelRef, OTHER);
        }
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
