package main

import "context"

// JourneyFulfillment boards + completes (or no-shows) a purchased entitlement.
func JourneyFulfillment(ctx context.Context, p *Providers) (string, error) {
	purchase := p.Reg.TakePurchase(p.Rng, "confirmed")
	if purchase == nil {
		return "no_purchase_to_fulfill", nil
	}

	if p.Chance("p_no_show") {
		_, record, err := p.API.Request(ctx, "POST", "fulfillment",
			"/api/v1/fulfillment-records/no-show",
			map[string]interface{}{
				"entitlementId":    purchase.Entitlement,
				"segmentBookingId": purchase.SB,
				"journeyOrderId":   purchase.Order,
				"travelerId":       purchase.Traveler,
				"segmentRef":       purchase.Seg,
				"reason":           "BOARDING_WINDOW_EXPIRED",
			}, nil, []int{200, 201}, "no-show")
		if err != nil {
			p.Reg.ReleasePurchase(purchase, "confirmed")
			return "", err
		}
		purchase.FulfillmentRecord = getString(record, "fulfillmentRecordId")
		p.Reg.ReleasePurchase(purchase, "fulfilled")
		return "no_show", nil
	}

	_, record, err := p.API.Request(ctx, "POST", "fulfillment",
		"/api/v1/fulfillment-records/boarding",
		map[string]interface{}{
			"entitlementId":    purchase.Entitlement,
			"segmentBookingId": purchase.SB,
			"journeyOrderId":   purchase.Order,
			"travelerId":       purchase.Traveler,
			"segmentRef":       purchase.Seg,
			"source":           "GATE",
			"sourceEventId":    "gate-" + UUID7(),
			"occurredAt":       NowISO(),
		}, nil, []int{200, 201}, "boarding")
	if err != nil {
		p.Reg.ReleasePurchase(purchase, "confirmed")
		return "", err
	}
	purchase.FulfillmentRecord = getString(record, "fulfillmentRecordId")

	Think(ctx, p)

	_, _, err = p.API.Request(ctx, "POST", "fulfillment",
		"/api/v1/fulfillment-records/completions",
		map[string]interface{}{
			"entitlementId":    purchase.Entitlement,
			"segmentBookingId": purchase.SB,
			"journeyOrderId":   purchase.Order,
			"travelerId":       purchase.Traveler,
			"segmentRef":       purchase.Seg,
			"completionSource": "ARRIVAL",
			"completedAt":      NowISO(),
		}, nil, []int{200, 201}, "completion")
	if err != nil {
		p.Reg.ReleasePurchase(purchase, "confirmed")
		return "", err
	}

	p.Reg.ReleasePurchase(purchase, "fulfilled")
	return "fulfilled", nil
}
