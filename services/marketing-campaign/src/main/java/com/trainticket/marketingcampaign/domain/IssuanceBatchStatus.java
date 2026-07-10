package com.trainticket.marketingcampaign.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

public enum IssuanceBatchStatus {
    PLANNED,
    RUNNING,
    PAUSED,
    PARTIALLY_SUCCEEDED,
    SUCCEEDED,
    PARTIALLY_FAILED,
    FAILED,
    MISSED,
    CANCELLED,
    CLOSED;

    private static final EnumMap<IssuanceBatchStatus, Set<IssuanceBatchStatus>> TRANSITIONS = new EnumMap<>(IssuanceBatchStatus.class);

    static {
        TRANSITIONS.put(PLANNED, EnumSet.of(RUNNING, CANCELLED, MISSED));
        TRANSITIONS.put(RUNNING, EnumSet.of(PARTIALLY_SUCCEEDED, SUCCEEDED, PARTIALLY_FAILED, FAILED, PAUSED));
        TRANSITIONS.put(PAUSED, EnumSet.of(RUNNING, CANCELLED, MISSED));
        TRANSITIONS.put(PARTIALLY_SUCCEEDED, EnumSet.of(RUNNING, PARTIALLY_FAILED, SUCCEEDED, CLOSED));
        TRANSITIONS.put(SUCCEEDED, EnumSet.of(CLOSED));
        TRANSITIONS.put(PARTIALLY_FAILED, EnumSet.of(CLOSED));
        TRANSITIONS.put(FAILED, EnumSet.of(CLOSED));
        TRANSITIONS.put(MISSED, EnumSet.of(CLOSED));
        TRANSITIONS.put(CANCELLED, EnumSet.of(CLOSED));
        TRANSITIONS.put(CLOSED, EnumSet.noneOf(IssuanceBatchStatus.class));
    }

    boolean canTransitionTo(IssuanceBatchStatus next) { return TRANSITIONS.get(this).contains(next); }
    boolean isClosedRestingState() { return this == CLOSED; }
    void requireCanTransitionTo(IssuanceBatchStatus next) {
        if (!canTransitionTo(next)) throw new DomainException("invalid issuance batch transition " + this + " -> " + next);
    }
}
