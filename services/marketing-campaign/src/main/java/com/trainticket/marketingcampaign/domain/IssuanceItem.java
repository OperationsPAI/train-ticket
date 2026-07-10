package com.trainticket.marketingcampaign.domain;

import java.time.Instant;
import java.util.Objects;

public record IssuanceItem(
    String issuanceItemId,
    String campaignId,
    String templateId,
    String accountId,
    String audienceSnapshotId,
    String idempotencyKey,
    Money amount,
    CampaignWindow benefitWindow,
    IssuanceItemStatus status,
    String walletBenefitId,
    String failureCode,
    int retryAttemptNo,
    Instant createdAt,
    Instant updatedAt
) {
    public IssuanceItem {
        issuanceItemId = Require.text(issuanceItemId, "issuanceItemId");
        campaignId = Require.text(campaignId, "campaignId");
        templateId = Require.text(templateId, "templateId");
        accountId = Require.text(accountId, "accountId");
        audienceSnapshotId = Require.text(audienceSnapshotId, "audienceSnapshotId");
        idempotencyKey = Require.text(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(amount, "amount").requirePositive("amount");
        Objects.requireNonNull(benefitWindow, "benefitWindow");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (status == IssuanceItemStatus.ACCEPTED) Require.text(walletBenefitId, "walletBenefitId");
        if (status == IssuanceItemStatus.FAILED) Require.text(failureCode, "failureCode");
    }

    IssuanceItemKey activeKey() { return new IssuanceItemKey(campaignId, templateId, accountId, audienceSnapshotId); }

    IssuanceItem accepted(String walletBenefitId, Instant now) {
        if (status == IssuanceItemStatus.FAILED) throw new DomainException("failed issuance item cannot be accepted");
        return new IssuanceItem(issuanceItemId, campaignId, templateId, accountId, audienceSnapshotId, idempotencyKey, amount,
            benefitWindow, IssuanceItemStatus.ACCEPTED, walletBenefitId, null, retryAttemptNo, createdAt, now);
    }

    IssuanceItem failed(String failureCode, Instant now) {
        if (status == IssuanceItemStatus.ACCEPTED) throw new DomainException("accepted issuance item cannot fail");
        return new IssuanceItem(issuanceItemId, campaignId, templateId, accountId, audienceSnapshotId, idempotencyKey, amount,
            benefitWindow, IssuanceItemStatus.FAILED, walletBenefitId, failureCode, retryAttemptNo, createdAt, now);
    }

    IssuanceItem retry(int retryAttemptNo, Instant now) {
        if (status != IssuanceItemStatus.FAILED) throw new DomainException("only failed issuance items can be retried");
        if (retryAttemptNo <= this.retryAttemptNo) throw new DomainException("retryAttemptNo must increase");
        return new IssuanceItem(issuanceItemId, campaignId, templateId, accountId, audienceSnapshotId, idempotencyKey, amount,
            benefitWindow, IssuanceItemStatus.RETRY_SCHEDULED, walletBenefitId, null, retryAttemptNo, createdAt, now);
    }
}
