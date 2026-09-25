package com.trainticket.postsales.observability;

/** The recorder a Post Sales process without an OpenTelemetry meter uses. */
public final class NoopPostSalesMetrics implements PostSalesMetricRecorder {
    public static final NoopPostSalesMetrics INSTANCE = new NoopPostSalesMetrics();

    private NoopPostSalesMetrics() {
    }

    @Override
    public void compensation(PostSalesMetricLabels.Outcome outcome, String currency) {
    }
}
