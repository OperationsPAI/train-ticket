package com.trainticket.postsales.observability;

/** Records the bounded compensation outcomes Post Sales owns. */
public interface PostSalesMetricRecorder {
    void compensation(PostSalesMetricLabels.Outcome outcome, String currency);
}
