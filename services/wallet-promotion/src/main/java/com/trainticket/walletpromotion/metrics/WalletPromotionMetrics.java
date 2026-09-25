package com.trainticket.walletpromotion.metrics;

import com.trainticket.walletpromotion.domain.BusinessReason;
import com.trainticket.walletpromotion.domain.ReasonType;

/**
 * Low-cardinality business telemetry for wallet benefit operations.
 *
 * <p>Every label is a closed enum owned here. Wallet, account, benefit,
 * redemption, reservation and reversal identifiers are useful in traces and
 * logs but would create a series per entity, so they never reach a metric.
 */
public interface WalletPromotionMetrics {
    enum Operation {
        ISSUE("issue"),
        RESERVE("reserve"),
        REDEEM("redeem"),
        RELEASE("release"),
        EXPIRE("expire"),
        REVERSE("reverse"),
        REVOKE("revoke");

        private final String value;

        Operation(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    enum Outcome {
        SUCCESS("success"),
        /** An idempotent replay: the request was already applied, nothing changed. */
        REPLAYED("replayed"),
        /** A domain or precondition rule refused the operation. */
        REJECTED("rejected");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /**
     * The owned reason vocabulary. Every value is derived from the domain's own
     * {@link ReasonType}; an absent or unrecognised reason becomes {@code other}
     * rather than copying free text from an exception.
     */
    enum ReasonCode {
        NONE("none"),
        MANUAL_OPS("manual_ops"),
        POST_SALES_COMP("post_sales_comp"),
        DISRUPTION_COMP("disruption_comp"),
        ORDER_PURCHASE("order_purchase"),
        RESERVATION_TIMEOUT("reservation_timeout"),
        CUSTOMER_SERVICE_ADJUSTMENT("customer_service_adjustment"),
        SYSTEM_EXPIRY("system_expiry"),
        REVERSAL("reversal"),
        OTHER("other");

        private final String value;

        ReasonCode(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static ReasonCode from(BusinessReason reason) {
            if (reason == null || reason.reasonType() == null) {
                return OTHER;
            }
            return switch (reason.reasonType()) {
                case MANUAL_OPS -> MANUAL_OPS;
                case POST_SALES_COMP -> POST_SALES_COMP;
                case DISRUPTION_COMP -> DISRUPTION_COMP;
                case ORDER_PURCHASE -> ORDER_PURCHASE;
                case RESERVATION_TIMEOUT -> RESERVATION_TIMEOUT;
                case CUSTOMER_SERVICE_ADJUSTMENT -> CUSTOMER_SERVICE_ADJUSTMENT;
                case SYSTEM_EXPIRY -> SYSTEM_EXPIRY;
                case REVERSAL -> REVERSAL;
            };
        }
    }

    /**
     * Records one terminal outcome. Money always carries a currency, so a
     * recorder that cannot name one records nothing.
     */
    void operation(Operation operation, Outcome outcome, ReasonCode reasonCode, String currency);

    static WalletPromotionMetrics noop() {
        return new NoOpWalletPromotionMetrics();
    }
}
