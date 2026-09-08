package com.trainticket.postsales.application;

/**
 * The order's policy context has not arrived yet, so a refund cannot be priced.
 *
 * WHY THIS IS AN ERROR RATHER THAN A DEFAULT
 * ------------------------------------------
 * The policy context carries the order's departure time and original fare, and
 * post-sales builds it from JourneyOrderCreated / JourneyOrderConfirmed. A
 * refund request can legitimately arrive before that event has been consumed --
 * they come from different services over different streams.
 *
 * The previous behaviour was to fall back to a synthetic context with
 * `departureTime = now`. RefundPolicyEngine reads that as zero time before
 * departure and returns AFTER_DEPARTURE_NON_REFUNDABLE: a 100% penalty and a
 * zero refund, on a journey that may be a month away. Downstream, payment's
 * handler returns early on the zero amount, so no RefundRequested is published
 * and the customer is simply never refunded. Every step of that succeeded.
 *
 * A retryable error is strictly better than a wrong number that no later step
 * can distinguish from a correct one. On the live cluster every order that hit
 * this had its context within seconds -- 505 misses in 15 minutes, all of which
 * had a context row by the time they were checked -- so the caller retrying is
 * the correct resolution, not a workaround.
 *
 * Deliberately NOT raised for CHANGE or COMPENSATION decisions: a zero refund is
 * a routine outcome for those, and the fallback context is harmless there.
 */
public class PolicyContextUnavailableException extends RuntimeException {
    private final String journeyOrderId;

    public PolicyContextUnavailableException(String journeyOrderId) {
        super("policy context for order " + journeyOrderId + " has not been projected yet; "
            + "refund pricing needs its departure time and original fare. Retry shortly.");
        this.journeyOrderId = journeyOrderId;
    }

    public String journeyOrderId() {
        return journeyOrderId;
    }
}
