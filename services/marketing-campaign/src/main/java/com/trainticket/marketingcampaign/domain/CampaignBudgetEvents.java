package com.trainticket.marketingcampaign.domain;

import java.time.Instant;

record CampaignBudgetSet(String aggregateId, String campaignId, Money totalBudget, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignBudgetReserved(String aggregateId, String campaignId, Money amount, String sourceRef, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignBudgetConsumed(String aggregateId, String campaignId, Money amount, String benefitId, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignBudgetReleased(String aggregateId, String campaignId, Money amount, String reason, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignBudgetClosed(String aggregateId, String campaignId, String reason, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
