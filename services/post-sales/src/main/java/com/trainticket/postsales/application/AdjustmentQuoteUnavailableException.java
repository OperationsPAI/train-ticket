package com.trainticket.postsales.application;

/**
 * The adjustment quote could not be read from fare-pricing.
 *
 * This is the retryable shape of that call: the upstream answered with a body
 * this client cannot decode, or the connection failed outright. It is not a
 * rejection of the request -- the same call answered QUOTED seconds later -- but
 * it also cannot be priced, so the caller is told to retry rather than given a
 * server error.
 *
 * Measured at revision 493: one request in 1.28 million answered 500 with
 * "RestClientException: Error while extracting response for type
 * [java.util.Map&lt;java.lang.String, java.lang.Object&gt;] and content type
 * [application/octet-stream]" on `POST /post-sales-cases/{id}/evaluate`, and the
 * case reached APPLIED two seconds later.
 */
public class AdjustmentQuoteUnavailableException extends RuntimeException {
    private final String journeyOrderId;

    public AdjustmentQuoteUnavailableException(String journeyOrderId, String detail) {
        super("adjustment quote for order " + journeyOrderId + " could not be read from fare-pricing: "
            + detail + ". Retry shortly.");
        this.journeyOrderId = journeyOrderId;
    }

    public String journeyOrderId() {
        return journeyOrderId;
    }
}
