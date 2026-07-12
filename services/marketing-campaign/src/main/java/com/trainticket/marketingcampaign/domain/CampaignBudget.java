package com.trainticket.marketingcampaign.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CampaignBudget {
    private final String budgetId;
    private final String campaignId;
    private final Money totalBudget;
    private final Money reservedAmount;
    private final Money consumedAmount;
    private final boolean closed;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;
    private final List<DomainEvent> domainEvents;

    private CampaignBudget(String budgetId, String campaignId, Money totalBudget, Money reservedAmount, Money consumedAmount,
                           boolean closed, Instant createdAt, Instant updatedAt, long version, List<DomainEvent> domainEvents) {
        this.budgetId = Require.text(budgetId, "budgetId");
        this.campaignId = Require.text(campaignId, "campaignId");
        this.totalBudget = Objects.requireNonNull(totalBudget, "totalBudget");
        this.reservedAmount = Objects.requireNonNull(reservedAmount, "reservedAmount");
        this.consumedAmount = Objects.requireNonNull(consumedAmount, "consumedAmount");
        totalBudget.requirePositive("totalBudget");
        totalBudget.requireSameCurrency(reservedAmount);
        totalBudget.requireSameCurrency(consumedAmount);
        reservedAmount.requireNonNegative("reservedAmount");
        consumedAmount.requireNonNegative("consumedAmount");
        if (reservedAmount.minorUnits() + consumedAmount.minorUnits() > totalBudget.minorUnits()) {
            throw new DomainException("reservedAmount + consumedAmount exceeds totalBudget");
        }
        this.closed = closed;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
        this.domainEvents = List.copyOf(domainEvents);
    }

    public static CampaignBudget set(String budgetId, String campaignId, Money totalBudget, Instant now) {
        totalBudget.requirePositive("totalBudget");
        List<DomainEvent> events = List.of(new CampaignBudgetSet(budgetId, campaignId, totalBudget, now, 1));
        return new CampaignBudget(budgetId, campaignId, totalBudget, Money.zero(totalBudget.currency()), Money.zero(totalBudget.currency()), false, now, now, 1, events);
    }

    public CampaignBudget reserve(Money amount, String sourceRef, Instant now) {
        requireOpen();
        amount.requirePositive("amount");
        Money nextReserved = reservedAmount.plus(amount);
        return change(nextReserved, consumedAmount, now, new CampaignBudgetReserved(budgetId, campaignId, amount, Require.text(sourceRef, "sourceRef"), now, version + 1), false);
    }

    public CampaignBudget consume(Money amount, String benefitId, Instant now) {
        requireOpen();
        amount.requirePositive("amount");
        if (reservedAmount.minorUnits() < amount.minorUnits()) throw new DomainException("consume amount exceeds reserved amount");
        Money nextReserved = reservedAmount.minus(amount);
        Money nextConsumed = consumedAmount.plus(amount);
        return change(nextReserved, nextConsumed, now, new CampaignBudgetConsumed(budgetId, campaignId, amount, Require.text(benefitId, "benefitId"), now, version + 1), false);
    }

    public CampaignBudget release(Money amount, String reason, Instant now) {
        requireOpen();
        amount.requirePositive("amount");
        if (reservedAmount.minorUnits() < amount.minorUnits()) throw new DomainException("release amount exceeds reserved amount");
        Money nextReserved = reservedAmount.minus(amount);
        return change(nextReserved, consumedAmount, now, new CampaignBudgetReleased(budgetId, campaignId, amount, Require.text(reason, "reason"), now, version + 1), false);
    }

    public CampaignBudget close(String reason, Instant now) {
        requireOpen();
        return change(reservedAmount, consumedAmount, now, new CampaignBudgetClosed(budgetId, campaignId, Require.text(reason, "reason"), now, version + 1), true);
    }

    private CampaignBudget change(Money nextReserved, Money nextConsumed, Instant now, DomainEvent event, boolean nextClosed) {
        ArrayList<DomainEvent> events = new ArrayList<>(domainEvents);
        events.add(event);
        return new CampaignBudget(budgetId, campaignId, totalBudget, nextReserved, nextConsumed, nextClosed, createdAt, Objects.requireNonNull(now, "now"), version + 1, events);
    }

    private void requireOpen() {
        if (closed) throw new DomainException("closed budget cannot be changed");
    }

    public static CampaignBudget restore(String budgetId, String campaignId, Money totalBudget, Money reservedAmount, Money consumedAmount,
                                         boolean closed, Instant createdAt, Instant updatedAt, long version) {
        return new CampaignBudget(budgetId, campaignId, totalBudget, reservedAmount, consumedAmount, closed, createdAt, updatedAt,
            version, List.of());
    }

    public String budgetId() { return budgetId; }
    public String campaignId() { return campaignId; }
    public Money totalBudget() { return totalBudget; }
    public Money reservedAmount() { return reservedAmount; }
    public Money consumedAmount() { return consumedAmount; }
    public boolean closed() { return closed; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
    public List<DomainEvent> domainEvents() { return domainEvents; }
}
