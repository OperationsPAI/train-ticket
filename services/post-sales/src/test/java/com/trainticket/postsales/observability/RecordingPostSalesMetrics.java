package com.trainticket.postsales.observability;

import java.util.ArrayList;
import java.util.List;

/** Captures the bounded compensation outcomes a recorder receives. */
public final class RecordingPostSalesMetrics implements PostSalesMetricRecorder {
    public record Compensation(PostSalesMetricLabels.Outcome outcome, String currency) {
    }

    private final List<Compensation> compensations = new ArrayList<>();

    @Override
    public void compensation(PostSalesMetricLabels.Outcome outcome, String currency) {
        compensations.add(new Compensation(outcome, PostSalesMetricLabels.currency(currency)));
    }

    public List<Compensation> compensations() {
        return List.copyOf(compensations);
    }
}
