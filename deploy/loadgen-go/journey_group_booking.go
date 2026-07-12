package main

import "context"

// JourneyGroupBooking creates a group booking for 10+ travelers.
func JourneyGroupBooking(ctx context.Context, p *Providers) (string, error) {
	entry, err := p.Account(ctx)
	if err != nil {
		return "", err
	}

	currency := p.Currency()
	fareMinor := p.DefaultAmount("group_fare_minor", 85000)
	discountBP := p.DefaultAmount("group_discount_basis_points", 500)

	_, group, err := p.API.Request(ctx, "POST", "group-booking", "/api/v1/group-bookings",
		map[string]interface{}{
			"organizerRef":        entry.AccountID,
			"segmentRefs":         []string{"seg-group-" + UUID7()[:8]},
			"targetTravelerCount": 10,
			"fare": map[string]interface{}{
				"currency":            currency,
				"minorUnits":          fareMinor,
				"discountBasisPoints": discountBP,
				"negotiationRef":      "nego-" + UUID7()[:8],
			},
		}, nil, []int{200, 201}, "group-create")
	if err != nil {
		return "", err
	}

	groupID := getString(group, "groupBookingId")
	if groupID == "" {
		groupID = getString(group, "id")
	}
	if groupID != "" {
		return "group_created", nil
	}
	return "group_failed", nil
}
