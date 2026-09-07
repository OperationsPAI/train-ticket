package main

import (
	"context"
	"time"
)

// JourneyCampaign drafts a marketing campaign (ops-side).
func JourneyCampaign(ctx context.Context, p *Providers) (string, error) {
	// NB: use the full UUID7, never a prefix. The first 8 hex chars of a UUIDv7
	// are the high 32 bits of the millisecond timestamp, so they are identical
	// for ~65.5s; marketing-campaign has a UNIQUE index on externalKey, so a
	// truncated key made every concurrent draft in that window collide with 409.
	extKey := "camp-" + UUID7()
	now := time.Now().UTC()
	windowEnd := now.Add(30 * 24 * time.Hour).Format(time.RFC3339)

	_, campaign, err := p.API.Request(ctx, "POST", "marketing-campaign", "/api/v1/campaigns",
		map[string]interface{}{
			"externalKey": extKey,
			"name":        "Campaign-" + UUID7(),
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
