package com.trainticket.postsales.application;

/** Refund and change pricing require the order's departure time and original fare. */
public class PolicyContextUnavailableException extends RuntimeException {
    private final String journeyOrderId;

    public PolicyContextUnavailableException(String journeyOrderId) {
        super("policy context for order " + journeyOrderId + " has not been projected yet; "
            + "post-sales pricing needs its departure time and original fare. Retry shortly.");
        this.journeyOrderId = journeyOrderId;
    }

    public String journeyOrderId() {
        return journeyOrderId;
    }
}
