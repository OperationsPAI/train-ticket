package main

import "context"

// JourneyLoyalty enrolls (idempotent) then reads the membership tier.
func JourneyLoyalty(ctx context.Context, p *Providers) (string, error) {
	entry, err := p.Account(ctx)
	if err != nil {
		return "", err
	}

	_, member, err := p.API.Request(ctx, "POST", "loyalty-membership", "/members/enroll",
		map[string]interface{}{"accountId": entry.AccountID},
		nil, []int{200, 201}, "loyalty-enroll")
	if err != nil {
		return "", err
	}

	memberID := getString(member, "memberId")
	if memberID == "" {
		return "loyalty_enroll_failed", nil
	}

	_, details, _ := p.API.Request(ctx, "GET", "loyalty-membership",
		"/members/"+memberID, nil, nil, []int{200, 404}, "loyalty-get-member")
	if details != nil && getString(details, "tier") != "" {
		return "loyalty_checked", nil
	}
	return "loyalty_enrolled", nil
}
