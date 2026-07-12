package com.trainticket.marketingcampaign.domain;

public enum IssuanceItemStatus {
    REQUESTED,
    ACCEPTED,
    FAILED,
    RETRY_SCHEDULED;

    boolean isActive() {
        return this == REQUESTED || this == RETRY_SCHEDULED;
    }
}
