package main

import (
	"context"
	"time"
)

// JourneyInsurance issues a travel insurance policy.
func JourneyInsurance(ctx context.Context, p *Providers) (string, error) {
	entry, err := p.Account(ctx)
	if err != nil {
		return "", err
	}
	tvl, err := p.Traveler(ctx, entry, nil)
	if err != nil {
		return "", err
	}

	now := time.Now().UTC()

	_, policy, err := p.API.Request(ctx, "POST", "travel-insurance", "/api/v1/policies",
		map[string]interface{}{
			"accountId":            entry.AccountID,
			"travelerRef":          tvl,
			"productCode":          "DELAY_INSURANCE",
			"productVersion":       "v1",
			"journeyOrderId":       "ord-" + UUID7(),
			"ancillaryOrderItemId": "anc-" + UUID7()[:8],
			"segmentRefs":          []string{"seg-ins-" + UUID7()[:8]},
			"paymentIntentId":      "pi-" + UUID7()[:8],
			"coverageStartAt":      now.Format(time.RFC3339),
			"coverageEndAt":        now.Add(24 * time.Hour).Format(time.RFC3339),
		}, nil, []int{200, 201}, "insurance-issue-policy")
	if err != nil {
		return "", err
	}

	if getString(policy, "policyId") != "" {
		return "insurance_policy_created", nil
	}
	return "insurance_policy_failed", nil
}
