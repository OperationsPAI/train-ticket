package com.trainticket.marketingcampaign.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CouponTemplate {
    private final String templateId;
    private final String campaignId;
    private final String templateCode;
    private final int templateVersion;
    private final CouponTemplateStatus status;
    private final Money faceValue;
    private final Money minimumSpend;
    private final String applicableScope;
    private final String redemptionRule;
    private final CampaignWindow validityWindow;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;
    private final List<DomainEvent> domainEvents;

    private CouponTemplate(String templateId, String campaignId, String templateCode, int templateVersion, CouponTemplateStatus status,
                           Money faceValue, Money minimumSpend, String applicableScope, String redemptionRule, CampaignWindow validityWindow,
                           Instant createdAt, Instant updatedAt, long version, List<DomainEvent> domainEvents) {
        this.templateId = Require.text(templateId, "templateId");
        this.campaignId = Require.text(campaignId, "campaignId");
        this.templateCode = Require.text(templateCode, "templateCode");
        if (templateVersion <= 0) throw new DomainException("templateVersion must be positive");
        this.templateVersion = templateVersion;
        this.status = Objects.requireNonNull(status, "status");
        this.faceValue = Objects.requireNonNull(faceValue, "faceValue");
        this.minimumSpend = Objects.requireNonNull(minimumSpend, "minimumSpend");
        faceValue.requirePositive("faceValue");
        minimumSpend.requireNonNegative("minimumSpend");
        faceValue.requireSameCurrency(minimumSpend);
        this.applicableScope = Require.text(applicableScope, "applicableScope");
        this.redemptionRule = Require.text(redemptionRule, "redemptionRule");
        this.validityWindow = Objects.requireNonNull(validityWindow, "validityWindow");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
        this.domainEvents = List.copyOf(domainEvents);
    }

    public static CouponTemplate draft(String templateId, String campaignId, String templateCode, int templateVersion,
                                       Money faceValue, Money minimumSpend, String applicableScope, String redemptionRule,
                                       CampaignWindow validityWindow, CampaignWindow campaignWindow, Instant now) {
        requireWithinCampaign(validityWindow, campaignWindow);
        List<DomainEvent> events = List.of(new CouponTemplateDrafted(templateId, campaignId, templateCode, templateVersion, now, 1));
        return new CouponTemplate(templateId, campaignId, templateCode, templateVersion, CouponTemplateStatus.DRAFT, faceValue,
            minimumSpend, applicableScope, redemptionRule, validityWindow, now, now, 1, events);
    }

    public CouponTemplate revise(Money faceValue, Money minimumSpend, String applicableScope, String redemptionRule,
                                 CampaignWindow validityWindow, CampaignWindow campaignWindow, Instant now) {
        requireDraftMutable();
        requireWithinCampaign(validityWindow, campaignWindow);
        return new CouponTemplate(templateId, campaignId, templateCode, templateVersion, status, faceValue, minimumSpend,
            applicableScope, redemptionRule, validityWindow, createdAt, Objects.requireNonNull(now, "now"), version + 1, domainEvents);
    }

    public CouponTemplate validate(Instant now) {
        return transition(CouponTemplateStatus.VALIDATED, now, new CouponTemplateValidated(templateId, campaignId, now, version + 1));
    }

    public CouponTemplate returnToDraft(Instant now) {
        return transition(CouponTemplateStatus.DRAFT, now, new CouponTemplateReopenedAsDraft(templateId, campaignId, now, version + 1));
    }

    public CouponTemplate publish(String approvalRef, Instant now) {
        return transition(CouponTemplateStatus.PUBLISHED, now, new CouponTemplatePublished(templateId, campaignId, templateCode, templateVersion, Require.text(approvalRef, "approvalRef"), now, version + 1));
    }

    public CouponTemplate supersede(String replacementTemplateId, Instant now) {
        return transition(CouponTemplateStatus.SUPERSEDED, now, new CouponTemplateSuperseded(templateId, campaignId, Require.text(replacementTemplateId, "replacementTemplateId"), now, version + 1));
    }

    public CouponTemplate retire(String reason, Instant now) {
        return transition(CouponTemplateStatus.RETIRED, now, new CouponTemplateRetired(templateId, campaignId, Require.text(reason, "reason"), now, version + 1));
    }

    private CouponTemplate transition(CouponTemplateStatus next, Instant now, DomainEvent event) {
        status.requireCanTransitionTo(next);
        ArrayList<DomainEvent> events = new ArrayList<>(domainEvents);
        events.add(event);
        return new CouponTemplate(templateId, campaignId, templateCode, templateVersion, next, faceValue, minimumSpend,
            applicableScope, redemptionRule, validityWindow, createdAt, Objects.requireNonNull(now, "now"), version + 1, events);
    }

    private void requireDraftMutable() {
        if (status.isPublishedImmutable()) throw new DomainException("published template changes require a new version");
        if (status != CouponTemplateStatus.DRAFT) throw new DomainException("template can only be revised in DRAFT");
    }

    private static void requireWithinCampaign(CampaignWindow validityWindow, CampaignWindow campaignWindow) {
        Objects.requireNonNull(validityWindow, "validityWindow");
        Objects.requireNonNull(campaignWindow, "campaignWindow");
        if (!campaignWindow.contains(validityWindow)) throw new DomainException("template validityWindow must be within campaign window");
    }

    public String templateId() { return templateId; }
    public String campaignId() { return campaignId; }
    public String templateCode() { return templateCode; }
    public int templateVersion() { return templateVersion; }
    public CouponTemplateStatus status() { return status; }
    public Money faceValue() { return faceValue; }
    public Money minimumSpend() { return minimumSpend; }
    public String applicableScope() { return applicableScope; }
    public String redemptionRule() { return redemptionRule; }
    public CampaignWindow validityWindow() { return validityWindow; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
    public List<DomainEvent> domainEvents() { return domainEvents; }
}
