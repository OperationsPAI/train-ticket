package main

import (
	"context"
	"time"
)

// JourneyCorporate creates a corporate travel agreement.
func JourneyCorporate(ctx context.Context, p *Providers) (string, error) {
	entry, err := p.Account(ctx)
	if err != nil {
		return "", err
	}
	_ = entry

	now := time.Now().UTC()

	_, agreement, err := p.API.Request(ctx, "POST", "corporate-travel", "/api/v1/agreements",
		map[string]interface{}{
			"corporateId":   "corp-" + UUID7()[:8],
			"agreementCode": "AGR-" + UUID7()[:8],
			"legalName":     "Corp-" + UUID7()[:8] + " Ltd.",
			"effectiveWindow": map[string]interface{}{
				"startsAt": now.Format(time.RFC3339),
				"endsAt":   now.Add(365 * 24 * time.Hour).Format(time.RFC3339),
			},
			"priceRef": map[string]interface{}{
				"fareRuleRefs":   []string{},
				"ruleSetId":      "rs-" + UUID7()[:8],
				"ruleSetVersion": "v1",
			},
			"monthlyCreditLimit": map[string]interface{}{
				"currency":   p.Currency(),
				"minorUnits": p.DefaultAmount("corporate_credit_limit_minor", 5000000),
			},
			"billingCalendar": map[string]interface{}{
				"billingPeriod": "MONTHLY",
				"cutoffAt":      now.Add(30 * 24 * time.Hour).Format(time.RFC3339),
				"dueAt":         now.Add(45 * 24 * time.Hour).Format(time.RFC3339),
			},
			"contact": map[string]interface{}{
				"email": "corp@example.com",
				"phone": "+86-10-12345678",
			},
			"activate": true,
		}, nil, []int{200, 201}, "corporate-create")
	if err != nil {
		return "", err
	}

	if getString(agreement, "agreementId") != "" {
		return "corporate_agreement_created", nil
	}
	return "corporate_agreement_failed", nil
}
