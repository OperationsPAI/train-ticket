package com.trainticket.payment.observability;

import com.trainticket.payment.domain.RefundStatus;
import java.util.Locale;

/**
 * The closed label vocabulary shared by every service that emits the
 * {@code train_ticket.payment.*} contract.
 *
 * <p>Payment, Payment Channel and Post Sales must agree on these strings, or the
 * same money movement would appear under two different series. Nothing derived
 * from a request -- an intent id, an order id, a channel transaction id, a
 * provider message -- is ever a label; unrecognized values collapse to
 * {@code other} so a caller cannot create a series.
 */
public final class PaymentMetricLabels {
    public static final String OTHER = "other";

    /** The four money movements the contract distinguishes. */
    public enum Operation {
        CAPTURE("capture"),
        REFUND("refund"),
        REVERSAL("reversal"),
        COMPENSATION("compensation");

        private final String value;

        Operation(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /**
     * Bounded outcomes. {@code pending} is a durable in-progress fact,
     * {@code missed} is a channel outcome that requires a query, and
     * {@code verified} is Post Sales' re-read of an already-issued credit.
     */
    public enum Outcome {
        SUCCEEDED("succeeded"),
        FAILED("failed"),
        REJECTED("rejected"),
        MISSED("missed"),
        PENDING("pending"),
        VERIFIED("verified"),
        OTHER("other");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private PaymentMetricLabels() {
    }

    /**
     * A three-letter ISO code is the currency axis. Anything else is
     * {@code other}, which keeps a malformed value from adding a series while
     * still preventing the sum of two currencies.
     */
    public static String currency(String currencyCode) {
        if (currencyCode == null) {
            return OTHER;
        }
        String normalized = currencyCode.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z]{3}") ? normalized : OTHER;
    }

    /**
     * Both the internal channel id ({@code ALIPAY}) and the channel-facing
     * simulated id ({@code ALIPAY_SIM}) normalize to the same bounded value.
     * {@code wallet} is the Post Sales compensation channel; it is not a
     * payment channel and never carries a captured or refunded amount.
     */
    public static String channel(String channelId) {
        if (channelId == null) {
            return OTHER;
        }
        return switch (channelId.trim().toUpperCase(Locale.ROOT)) {
            case "ALIPAY", "ALIPAY_SIM" -> "alipay";
            case "WECHAT", "WECHAT_PAY", "WECHAT_SIM" -> "wechat_pay";
            case "UNIONPAY", "UNIONPAY_SIM" -> "unionpay";
            case "WALLET", "PROMOTION_CREDIT" -> "wallet";
            default -> OTHER;
        };
    }

    public static Outcome refundOutcome(RefundStatus status) {
        return switch (status) {
            case SETTLED -> Outcome.SUCCEEDED;
            case FAILED -> Outcome.FAILED;
            case MANUAL_REVIEW_REQUIRED -> Outcome.REJECTED;
            case REQUESTED, SUBMITTED -> Outcome.PENDING;
        };
    }
}
