package com.trainticket.postsales.observability;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The bounded label vocabulary Post Sales contributes to the shared
 * {@code train_ticket.payment.operations} contract.
 *
 * <p>Post Sales owns exactly the compensation outcome. It must not publish an
 * amount or a conservation balance: the wallet credit is recorded by
 * wallet-promotion, and the captured payment balance by payment. Inventing a
 * second money source here would double count every compensation.
 */
public final class PostSalesMetricLabels {
    public static final String OTHER = "other";
    public static final String OPERATION_COMPENSATION = "compensation";
    public static final String CHANNEL_WALLET = "wallet";

    public enum Outcome {
        PENDING("pending"),
        SUCCEEDED("succeeded"),
        VERIFIED("verified"),
        REJECTED("rejected"),
        OTHER("other");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private PostSalesMetricLabels() {
    }

    /**
     * Maps the persisted compensation step name onto a closed outcome. An
     * unrecognized step becomes {@code other} rather than a series of its own.
     */
    public static Outcome compensationOutcome(String operationName) {
        if (operationName == null) {
            return Outcome.OTHER;
        }
        return switch (operationName) {
            case "request" -> Outcome.PENDING;
            case "issued" -> Outcome.SUCCEEDED;
            case "verified" -> Outcome.VERIFIED;
            default -> Outcome.OTHER;
        };
    }

    public static String currency(String currencyCode) {
        if (currencyCode == null) {
            return OTHER;
        }
        String normalized = currencyCode.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z]{3}") ? normalized : OTHER;
    }

    /**
     * Reads the currency off the recorded compensation evidence without taking a
     * monetary total: the amount itself stays owned by wallet-promotion.
     */
    public static String currencyOf(Object compensationResult) {
        if (compensationResult instanceof Map<?, ?> result) {
            for (String key : List.of("amount", "issuedAmount", "refundAmount")) {
                if (result.get(key) instanceof Map<?, ?> money && money.get("currency") != null) {
                    return currency(String.valueOf(money.get("currency")));
                }
            }
        }
        return OTHER;
    }
}
