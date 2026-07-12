package com.trainticket.marketingcampaign.domain;

import java.time.Instant;

public interface DomainEvent {
    String aggregateId();
    Instant occurredAt();
    long aggregateVersion();
}
