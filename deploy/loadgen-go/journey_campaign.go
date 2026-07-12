package main

import (
	"context"
	"time"
)

// JourneyCampaign drafts a marketing campaign (ops-side).
func JourneyCampaign(ctx context.Context, p *Providers) (string, error) {
	extKey := "camp-" + UUID7()[:8]
	now := time.Now().UTC()
	windowEnd := now.Add(30 * 24 * time.Hour).Format(time.RFC3339)

	_, campaign, err := p.API.Request(ctx, "POST", "marketing-campaign", "/api/v1/campaigns",
		map[string]interface{}{
			"externalKey": extKey,
			"name":        "Campaign-" + UUID7()[:8],
			"window": map[string]interface{}{
				"validFrom":  now.Format(time.RFC3339),
				"validUntil": windowEnd,
			},
		}, nil, []int{200, 201}, "campaign-draft")
	if err != nil {
		return "", err
	}

	campaignID := getString(campaign, "campaignId")
	if campaignID == "" {
		campaignID = getString(campaign, "id")
	}
	if campaignID != "" {
		return "campaign_drafted", nil
	}
	return "campaign_draft_failed", nil
}
