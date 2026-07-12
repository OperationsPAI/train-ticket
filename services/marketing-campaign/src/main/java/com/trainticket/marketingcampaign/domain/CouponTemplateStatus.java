package com.trainticket.marketingcampaign.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

public enum CouponTemplateStatus {
    DRAFT,
    VALIDATED,
    PUBLISHED,
    SUPERSEDED,
    RETIRED;

    private static final EnumMap<CouponTemplateStatus, Set<CouponTemplateStatus>> TRANSITIONS = new EnumMap<>(CouponTemplateStatus.class);

    static {
        TRANSITIONS.put(DRAFT, EnumSet.of(VALIDATED, RETIRED));
        TRANSITIONS.put(VALIDATED, EnumSet.of(PUBLISHED, DRAFT, RETIRED));
        TRANSITIONS.put(PUBLISHED, EnumSet.of(RETIRED, SUPERSEDED));
        TRANSITIONS.put(SUPERSEDED, EnumSet.of(RETIRED));
        TRANSITIONS.put(RETIRED, EnumSet.noneOf(CouponTemplateStatus.class));
    }

    boolean canTransitionTo(CouponTemplateStatus next) {
        return TRANSITIONS.get(this).contains(next);
    }

    boolean isPublishedImmutable() {
        return this == PUBLISHED || this == SUPERSEDED || this == RETIRED;
    }

    void requireCanTransitionTo(CouponTemplateStatus next) {
        if (!canTransitionTo(next)) throw new DomainException("invalid coupon template transition " + this + " -> " + next);
    }
}
