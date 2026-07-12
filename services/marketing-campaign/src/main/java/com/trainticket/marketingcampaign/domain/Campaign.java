package com.trainticket.marketingcampaign.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Campaign {
    private final String campaignId;
    private final String externalKey;
    private final String name;
    private final CampaignWindow window;
    private final String targetRuleSetId;
    private final String budgetId;
    private final String approvalRef;
    private final CampaignStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;
    private final List<DomainEvent> domainEvents;

    private Campaign(String campaignId, String externalKey, String name, CampaignWindow window, String targetRuleSetId,
                     String budgetId, String approvalRef, CampaignStatus status, Instant createdAt, Instant updatedAt,
                     long version, List<DomainEvent> domainEvents) {
        this.campaignId = Require.text(campaignId, "campaignId");
        this.externalKey = Require.text(externalKey, "externalKey");
        this.name = Require.text(name, "name");
        this.window = Objects.requireNonNull(window, "window");
        this.targetRuleSetId = targetRuleSetId;
        this.budgetId = budgetId;
        this.approvalRef = approvalRef;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
        this.domainEvents = List.copyOf(domainEvents);
    }

    public static Campaign draft(String campaignId, String externalKey, String name, CampaignWindow window, Instant now) {
        List<DomainEvent> events = List.of(new CampaignDrafted(campaignId, externalKey, name, window, now, 1));
        return new Campaign(campaignId, externalKey, name, window, null, null, null, CampaignStatus.DRAFT, now, now, 1, events);
    }

    public Campaign withLaunchReadiness(String budgetId, String targetRuleSetId) {
        requireMutable();
        return new Campaign(campaignId, externalKey, name, window, Require.text(targetRuleSetId, "targetRuleSetId"),
            Require.text(budgetId, "budgetId"), approvalRef, status, createdAt, updatedAt, version, domainEvents);
    }

    public Campaign withLaunchReadiness(String budgetId, String targetRuleSetId, Instant now) {
        requireMutable();
        return new Campaign(campaignId, externalKey, name, window, Require.text(targetRuleSetId, "targetRuleSetId"),
            Require.text(budgetId, "budgetId"), approvalRef, status, createdAt, Objects.requireNonNull(now, "now"), version + 1, domainEvents);
    }

    public Campaign submitForReview(Instant now) {
        return transition(CampaignStatus.IN_REVIEW, now, new CampaignSubmittedForReview(campaignId, now, version + 1), approvalRef);
    }

    public Campaign reject(String reviewRef, Instant now) {
        return transition(CampaignStatus.REJECTED, now, new CampaignRejected(campaignId, Require.text(reviewRef, "reviewRef"), now, version + 1), approvalRef);
    }

    public Campaign reviseDraft(Instant now) {
        return transition(CampaignStatus.DRAFT, now, new CampaignReopenedAsDraft(campaignId, now, version + 1), approvalRef);
    }

    public Campaign approve(String approvalRef, Instant now) {
        String checkedApprovalRef = Require.text(approvalRef, "approvalRef");
        return transition(CampaignStatus.APPROVED, now, new CampaignApproved(campaignId, checkedApprovalRef, now, version + 1), checkedApprovalRef);
    }

    public Campaign schedule(Instant now) {
        status.requireCanTransitionTo(CampaignStatus.SCHEDULED);
        requireLaunchReadiness();
        return transition(CampaignStatus.SCHEDULED, now, new CampaignScheduled(campaignId, window, now, version + 1), approvalRef);
    }

    public Campaign start(Instant now) {
        if (now.isBefore(window.validFrom())) throw new DomainException("campaign cannot start before validFrom");
        return transition(CampaignStatus.ACTIVE, now, new CampaignStarted(campaignId, now, version + 1), approvalRef);
    }

    public Campaign pause(String reason, String operatorRef, Instant now) {
        return transition(CampaignStatus.PAUSED, now, new CampaignPaused(campaignId, Require.text(reason, "reason"), Require.text(operatorRef, "operatorRef"), now, version + 1), approvalRef);
    }

    public Campaign resume(String reason, String operatorRef, Instant now) {
        return transition(CampaignStatus.ACTIVE, now, new CampaignResumed(campaignId, Require.text(reason, "reason"), Require.text(operatorRef, "operatorRef"), now, version + 1), approvalRef);
    }

    public Campaign beginCompletion(String reason, Instant now) {
        return transition(CampaignStatus.COMPLETING, now, new CampaignCompletionStarted(campaignId, Require.text(reason, "reason"), now, version + 1), approvalRef);
    }

    public Campaign complete(String reason, Instant now) {
        return transition(CampaignStatus.COMPLETED, now, new CampaignCompleted(campaignId, Require.text(reason, "reason"), now, version + 1), approvalRef);
    }

    public Campaign fail(String failureCode, Instant now) {
        return transition(CampaignStatus.FAILED, now, new CampaignFailed(campaignId, Require.text(failureCode, "failureCode"), now, version + 1), approvalRef);
    }

    public Campaign cancel(String reason, String operatorRef, Instant now) {
        return transition(CampaignStatus.CANCELLED, now, new CampaignCancelled(campaignId, Require.text(reason, "reason"), Require.text(operatorRef, "operatorRef"), now, version + 1), approvalRef);
    }

    private Campaign transition(CampaignStatus next, Instant now, DomainEvent event, String nextApprovalRef) {
        status.requireCanTransitionTo(next);
        ArrayList<DomainEvent> events = new ArrayList<>(domainEvents);
        events.add(event);
        return new Campaign(campaignId, externalKey, name, window, targetRuleSetId, budgetId, nextApprovalRef, next,
            createdAt, Objects.requireNonNull(now, "now"), version + 1, events);
    }

    private void requireLaunchReadiness() {
        Require.text(budgetId, "budgetId");
        Require.text(targetRuleSetId, "targetRuleSetId");
        Require.text(approvalRef, "approvalRef");
    }

    private void requireMutable() {
        if (status.isTerminal()) throw new DomainException("terminal campaign cannot be changed");
    }

    public static Campaign restore(String campaignId, String externalKey, String name, CampaignWindow window, String targetRuleSetId,
                                   String budgetId, String approvalRef, CampaignStatus status, Instant createdAt, Instant updatedAt,
                                   long version) {
        return new Campaign(campaignId, externalKey, name, window, targetRuleSetId, budgetId, approvalRef, status, createdAt, updatedAt,
            version, List.of());
    }

    public String campaignId() { return campaignId; }
    public String externalKey() { return externalKey; }
    public String name() { return name; }
    public CampaignWindow window() { return window; }
    public String targetRuleSetId() { return targetRuleSetId; }
    public String budgetId() { return budgetId; }
    public String approvalRef() { return approvalRef; }
    public CampaignStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
    public List<DomainEvent> domainEvents() { return domainEvents; }
}
