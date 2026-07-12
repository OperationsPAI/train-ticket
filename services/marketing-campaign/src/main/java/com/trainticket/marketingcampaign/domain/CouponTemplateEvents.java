package com.trainticket.marketingcampaign.domain;

import java.time.Instant;

record CouponTemplateDrafted(String aggregateId, String campaignId, String templateCode, int templateVersion, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CouponTemplateValidated(String aggregateId, String campaignId, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CouponTemplateReopenedAsDraft(String aggregateId, String campaignId, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CouponTemplatePublished(String aggregateId, String campaignId, String templateCode, int templateVersion, String approvalRef, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CouponTemplateSuperseded(String aggregateId, String campaignId, String replacementTemplateId, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
record CouponTemplateRetired(String aggregateId, String campaignId, String reason, Instant occurredAt, long aggregateVersion) implements DomainEvent {}
