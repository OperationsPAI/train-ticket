package com.trainticket.marketingcampaign.domain;

import java.time.Instant;

record IssuanceBatchPlanned(String aggregateId, String campaignId, String templateId, String audienceSnapshotId, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record IssuanceBatchStarted(String aggregateId, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record IssuanceBatchPaused(String aggregateId, String reason, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record IssuanceBatchProgressChanged(String aggregateId, IssuanceBatchStatus finalStatus, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record IssuanceBatchFailed(String aggregateId, String failureCode, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record IssuanceBatchMissed(String aggregateId, String reason, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record IssuanceBatchCancelled(String aggregateId, String reason, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CampaignCouponIssueRequested(String aggregateId, String issuanceItemId, String accountId, String idempotencyKey, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record WalletIssuanceAccepted(String aggregateId, String issuanceItemId, String walletBenefitId, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record IssuanceItemFailed(String aggregateId, String issuanceItemId, String failureCode, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record IssuanceItemRetryScheduled(String aggregateId, String issuanceItemId, int retryAttemptNo, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record IssuanceBatchClosed(String aggregateId, String reason, IssuanceBatchStatus finalStatus, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
