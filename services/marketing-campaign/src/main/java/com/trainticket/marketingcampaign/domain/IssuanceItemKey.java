package com.trainticket.marketingcampaign.domain;

record IssuanceItemKey(String campaignId, String templateId, String accountId, String audienceSnapshotId) {
    IssuanceItemKey {
        campaignId = Require.text(campaignId, "campaignId");
        templateId = Require.text(templateId, "templateId");
        accountId = Require.text(accountId, "accountId");
        audienceSnapshotId = Require.text(audienceSnapshotId, "audienceSnapshotId");
    }
}
