package com.trainticket.marketingcampaign.domain;

import java.time.Instant;

record CampaignDrafted(String aggregateId, String externalKey, String name, CampaignWindow window, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignSubmittedForReview(String aggregateId, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignRejected(String aggregateId, String reviewRef, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignReopenedAsDraft(String aggregateId, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignApproved(String aggregateId, String approvalRef, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignScheduled(String aggregateId, CampaignWindow window, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignStarted(String aggregateId, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignPaused(String aggregateId, String reason, String operatorRef, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignResumed(String aggregateId, String reason, String operatorRef, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignCompletionStarted(String aggregateId, String reason, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignCompleted(String aggregateId, String reason, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignFailed(String aggregateId, String failureCode, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignCancelled(String aggregateId, String reason, String operatorRef, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
