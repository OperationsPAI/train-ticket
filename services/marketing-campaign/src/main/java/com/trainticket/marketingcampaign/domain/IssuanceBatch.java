package com.trainticket.marketingcampaign.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class IssuanceBatch {
    private final String issuanceBatchId;
    private final String campaignId;
    private final String templateId;
    private final String audienceSnapshotId;
    private final IssuanceBatchStatus status;
    private final Map<String, IssuanceItem> itemsById;
    private final Instant plannedAt;
    private final Instant updatedAt;
    private final long version;
    private final List<DomainEvent> domainEvents;

    private IssuanceBatch(String issuanceBatchId, String campaignId, String templateId, String audienceSnapshotId,
                          IssuanceBatchStatus status, Map<String, IssuanceItem> itemsById, Instant plannedAt,
                          Instant updatedAt, long version, List<DomainEvent> domainEvents) {
        this.issuanceBatchId = Require.text(issuanceBatchId, "issuanceBatchId");
        this.campaignId = Require.text(campaignId, "campaignId");
        this.templateId = Require.text(templateId, "templateId");
        this.audienceSnapshotId = Require.text(audienceSnapshotId, "audienceSnapshotId");
        this.status = Objects.requireNonNull(status, "status");
        this.itemsById = Map.copyOf(itemsById);
        enforceActiveItemUniqueness(this.itemsById);
        this.plannedAt = Objects.requireNonNull(plannedAt, "plannedAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
        this.domainEvents = List.copyOf(domainEvents);
    }

    public static IssuanceBatch plan(String issuanceBatchId, String campaignId, String templateId, String audienceSnapshotId, Instant now) {
        List<DomainEvent> events = List.of(new IssuanceBatchPlanned(issuanceBatchId, campaignId, templateId, audienceSnapshotId, now, 1));
        return new IssuanceBatch(issuanceBatchId, campaignId, templateId, audienceSnapshotId, IssuanceBatchStatus.PLANNED,
            Map.of(), now, now, 1, events);
    }

    public IssuanceBatch start(Instant now) {
        return transition(IssuanceBatchStatus.RUNNING, now, new IssuanceBatchStarted(issuanceBatchId, now, version + 1));
    }

    public IssuanceBatch pause(String reason, Instant now) {
        return transition(IssuanceBatchStatus.PAUSED, now, new IssuanceBatchPaused(issuanceBatchId, Require.text(reason, "reason"), now, version + 1));
    }

    public IssuanceBatch markPartiallySucceeded(Instant now) {
        return transition(IssuanceBatchStatus.PARTIALLY_SUCCEEDED, now, new IssuanceBatchProgressChanged(issuanceBatchId, IssuanceBatchStatus.PARTIALLY_SUCCEEDED, now, version + 1));
    }

    public IssuanceBatch markSucceeded(Instant now) {
        return transition(IssuanceBatchStatus.SUCCEEDED, now, new IssuanceBatchProgressChanged(issuanceBatchId, IssuanceBatchStatus.SUCCEEDED, now, version + 1));
    }

    public IssuanceBatch markPartiallyFailed(Instant now) {
        return transition(IssuanceBatchStatus.PARTIALLY_FAILED, now, new IssuanceBatchProgressChanged(issuanceBatchId, IssuanceBatchStatus.PARTIALLY_FAILED, now, version + 1));
    }

    public IssuanceBatch fail(String failureCode, Instant now) {
        return transition(IssuanceBatchStatus.FAILED, now, new IssuanceBatchFailed(issuanceBatchId, Require.text(failureCode, "failureCode"), now, version + 1));
    }

    public IssuanceBatch miss(String reason, Instant now) {
        return transition(IssuanceBatchStatus.MISSED, now, new IssuanceBatchMissed(issuanceBatchId, Require.text(reason, "reason"), now, version + 1));
    }

    public IssuanceBatch cancel(String reason, Instant now) {
        return transition(IssuanceBatchStatus.CANCELLED, now, new IssuanceBatchCancelled(issuanceBatchId, Require.text(reason, "reason"), now, version + 1));
    }

    public IssuanceBatch close(String reason, Instant now) {
        return transition(IssuanceBatchStatus.CLOSED, now, new IssuanceBatchClosed(issuanceBatchId, Require.text(reason, "reason"), status, now, version + 1));
    }

    public IssueItemResult issueCampaignCoupon(String issuanceItemId, String accountId, String idempotencyKey, Money amount,
                                               CampaignWindow benefitWindow, Instant now) {
        requireRunningForItemChanges();
        Optional<IssuanceItem> replay = findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) return new IssueItemResult(this, replay.get(), true);
        IssuanceItem item = new IssuanceItem(issuanceItemId, campaignId, templateId, accountId, audienceSnapshotId,
            idempotencyKey, amount, benefitWindow, IssuanceItemStatus.REQUESTED, null, null, 0, now, now);
        Map<String, IssuanceItem> nextItems = new LinkedHashMap<>(itemsById);
        if (nextItems.containsKey(item.issuanceItemId())) throw new DomainException("issuanceItemId already exists");
        nextItems.put(item.issuanceItemId(), item);
        return new IssueItemResult(changeItems(nextItems, now, new CampaignCouponIssueRequested(issuanceBatchId, item.issuanceItemId(), item.accountId(), item.idempotencyKey(), now, version + 1)), item, false);
    }

    public IssuanceBatch recordWalletIssuanceAccepted(String issuanceItemId, String walletBenefitId, Instant now) {
        requireNotClosed();
        IssuanceItem item = requireItem(issuanceItemId).accepted(walletBenefitId, now);
        return replaceItem(item, now, new WalletIssuanceAccepted(issuanceBatchId, issuanceItemId, walletBenefitId, now, version + 1));
    }

    public IssuanceBatch recordIssuanceItemFailed(String issuanceItemId, String failureCode, Instant now) {
        requireNotClosed();
        IssuanceItem item = requireItem(issuanceItemId).failed(failureCode, now);
        return replaceItem(item, now, new IssuanceItemFailed(issuanceBatchId, issuanceItemId, failureCode, now, version + 1));
    }

    public IssuanceBatch retryIssuanceItem(String issuanceItemId, int retryAttemptNo, Instant now) {
        requireRunningForItemChanges();
        IssuanceItem item = requireItem(issuanceItemId).retry(retryAttemptNo, now);
        return replaceItem(item, now, new IssuanceItemRetryScheduled(issuanceBatchId, issuanceItemId, retryAttemptNo, now, version + 1));
    }

    private IssuanceBatch transition(IssuanceBatchStatus next, Instant now, DomainEvent event) {
        status.requireCanTransitionTo(next);
        return changeItems(itemsById, now, event, next);
    }

    private IssuanceBatch replaceItem(IssuanceItem item, Instant now, DomainEvent event) {
        Map<String, IssuanceItem> nextItems = new LinkedHashMap<>(itemsById);
        nextItems.put(item.issuanceItemId(), item);
        return changeItems(nextItems, now, event);
    }

    private IssuanceBatch changeItems(Map<String, IssuanceItem> nextItems, Instant now, DomainEvent event) {
        return changeItems(nextItems, now, event, status);
    }

    private IssuanceBatch changeItems(Map<String, IssuanceItem> nextItems, Instant now, DomainEvent event, IssuanceBatchStatus nextStatus) {
        ArrayList<DomainEvent> events = new ArrayList<>(domainEvents);
        events.add(event);
        return new IssuanceBatch(issuanceBatchId, campaignId, templateId, audienceSnapshotId, nextStatus, nextItems,
            plannedAt, Objects.requireNonNull(now, "now"), version + 1, events);
    }

    private void requireRunningForItemChanges() {
        if (!(status == IssuanceBatchStatus.RUNNING || status == IssuanceBatchStatus.PARTIALLY_SUCCEEDED)) {
            throw new DomainException("issuance items cannot be changed while batch is " + status);
        }
    }

    private void requireNotClosed() {
        if (status.isClosedRestingState()) throw new DomainException("closed batch cannot be changed");
    }

    private IssuanceItem requireItem(String issuanceItemId) {
        IssuanceItem item = itemsById.get(Require.text(issuanceItemId, "issuanceItemId"));
        if (item == null) throw new DomainException("issuance item not found");
        return item;
    }

    private Optional<IssuanceItem> findByIdempotencyKey(String idempotencyKey) {
        String checked = Require.text(idempotencyKey, "idempotencyKey");
        return itemsById.values().stream().filter(item -> item.idempotencyKey().equals(checked)).findFirst();
    }

    private static void enforceActiveItemUniqueness(Map<String, IssuanceItem> items) {
        List<IssuanceItemKey> activeKeys = new ArrayList<>();
        for (IssuanceItem item : items.values()) {
            if (item.status().isActive()) {
                IssuanceItemKey key = item.activeKey();
                if (activeKeys.contains(key)) throw new DomainException("active issuance item already exists for campaign/template/account/audience");
                activeKeys.add(key);
            }
        }
    }

    public static IssuanceBatch restore(String issuanceBatchId, String campaignId, String templateId, String audienceSnapshotId,
                                        IssuanceBatchStatus status, Map<String, IssuanceItem> itemsById, Instant plannedAt,
                                        Instant updatedAt, long version) {
        return new IssuanceBatch(issuanceBatchId, campaignId, templateId, audienceSnapshotId, status, itemsById, plannedAt,
            updatedAt, version, List.of());
    }

    public String issuanceBatchId() { return issuanceBatchId; }
    public String campaignId() { return campaignId; }
    public String templateId() { return templateId; }
    public String audienceSnapshotId() { return audienceSnapshotId; }
    public IssuanceBatchStatus status() { return status; }
    public Map<String, IssuanceItem> itemsById() { return itemsById; }
    public Instant plannedAt() { return plannedAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
    public List<DomainEvent> domainEvents() { return domainEvents; }

    public record IssueItemResult(IssuanceBatch batch, IssuanceItem item, boolean replayed) {}
}
