package com.trainticket.marketingcampaign.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

public enum CampaignStatus {
    DRAFT,
    IN_REVIEW,
    REJECTED,
    APPROVED,
    SCHEDULED,
    ACTIVE,
    PAUSED,
    COMPLETING,
    COMPLETED,
    FAILED,
    CANCELLED;

    private static final EnumMap<CampaignStatus, Set<CampaignStatus>> TRANSITIONS = new EnumMap<>(CampaignStatus.class);

    static {
        TRANSITIONS.put(DRAFT, EnumSet.of(IN_REVIEW, CANCELLED));
        TRANSITIONS.put(IN_REVIEW, EnumSet.of(APPROVED, REJECTED, DRAFT, CANCELLED));
        TRANSITIONS.put(REJECTED, EnumSet.of(DRAFT, CANCELLED));
        TRANSITIONS.put(APPROVED, EnumSet.of(SCHEDULED, CANCELLED));
        TRANSITIONS.put(SCHEDULED, EnumSet.of(ACTIVE, CANCELLED, FAILED));
        TRANSITIONS.put(ACTIVE, EnumSet.of(PAUSED, COMPLETING, FAILED, CANCELLED));
        TRANSITIONS.put(PAUSED, EnumSet.of(ACTIVE, COMPLETING, FAILED, CANCELLED));
        TRANSITIONS.put(COMPLETING, EnumSet.of(COMPLETED, FAILED));
        TRANSITIONS.put(COMPLETED, EnumSet.noneOf(CampaignStatus.class));
        TRANSITIONS.put(FAILED, EnumSet.noneOf(CampaignStatus.class));
        TRANSITIONS.put(CANCELLED, EnumSet.noneOf(CampaignStatus.class));
    }

    public boolean canTransitionTo(CampaignStatus next) {
        return TRANSITIONS.get(this).contains(next);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public void requireCanTransitionTo(CampaignStatus next) {
        if (!canTransitionTo(next)) throw new DomainException("invalid campaign transition " + this + " -> " + next);
    }
}
