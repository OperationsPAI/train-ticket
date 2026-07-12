package com.trainticket.postsales.domain;

public enum RefundClassification {
    VOLUNTARY,
    INVOLUNTARY_CARRIER,
    INVOLUNTARY_DELAY,
    INVOLUNTARY_FORCE_MAJEURE,
    INVOLUNTARY_PLATFORM;

    public boolean isInvoluntary() {
        return this != VOLUNTARY;
    }
}
