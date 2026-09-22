package main

import (
	"context"
	"time"
)

// JourneyFulfillment boards + completes (or no-shows) a purchased entitlement.
func JourneyFulfillment(ctx context.Context, p *Providers) (string, error) {
	purchase := p.Reg.TakePurchase(p.Rng, "confirmed")
	if purchase == nil {
		return "no_purchase_to_fulfill", nil
	}

	if p.Chance("p_no_show") {
		_, record, err := fulfillmentRequest(ctx, p,
			"/api/v1/fulfillment-records/no-show",
			map[string]interface{}{
				"entitlementId":    purchase.Entitlement,
				"segmentBookingId": purchase.SB,
				"journeyOrderId":   purchase.Order,
				"travelerId":       purchase.Traveler,
				"segmentRef":       purchase.Seg,
				"reason":           "BOARDING_WINDOW_EXPIRED",
			}, "no-show")
		if err != nil {
			p.Reg.ReleasePurchase(purchase, "confirmed")
			return "", err
		}
		purchase.FulfillmentRecord = getString(record, "fulfillmentRecordId")
		p.Reg.ReleasePurchase(purchase, "fulfilled")
		return "no_show", nil
	}

	_, record, err := fulfillmentRequest(ctx, p,
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
		}, "boarding")
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
	MaybeReadProbe(ctx, p, ProbeRefs{
		FulfillmentRecord: purchase.FulfillmentRecord,
		Entitlement:       purchase.Entitlement,
		Order:             purchase.Order,
		Account:           purchase.Account,
	})
	return "fulfilled", nil
}

func fulfillmentRequest(ctx context.Context, p *Providers, path string, body map[string]interface{}, step string) (int, map[string]interface{}, error) {
	headers := map[string]string{"Idempotency-Key": UUID7()}
	for attempt := 0; ; attempt++ {
		var expected []int
		if attempt < p.Cfg.Polling.Attempts-1 {
			expected = []int{404}
		}
		code, data, err := p.API.PollRequest(ctx, "POST", "fulfillment", path, body, headers, []int{200, 201}, step, expected)
		if code != 404 || attempt >= p.Cfg.Polling.Attempts-1 {
			return code, data, err
		}
		select {
		case <-ctx.Done():
			return 0, nil, ctx.Err()
		case <-time.After(time.Duration(p.Cfg.Polling.IntervalSeconds * float64(time.Second))):
		}
	}
}
