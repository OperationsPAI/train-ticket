package main

import (
	"context"
	"net/url"
	"time"
)

// JourneyRide dispatches a ride request lifecycle.
func JourneyRide(ctx context.Context, p *Providers) (string, error) {
	entry, err := p.Account(ctx)
	if err != nil {
		return "", err
	}
	traveler, err := p.Traveler(ctx, entry, nil)
	if err != nil {
		return "", err
	}

	windowStart := time.Now().UTC().Add(2 * time.Minute)
	windowEnd := windowStart.Add(30 * time.Minute)
	suffix := UUID7()

	_, ride, err := p.API.Request(ctx, "POST", "dispatch", "/api/v1/ride-requests",
		map[string]interface{}{
			"pickupRef":  "plc-ride-pick-" + suffix,
			"dropoffRef": "plc-ride-drop-" + suffix,
			"timeWindow": map[string]interface{}{
				"startAt": windowStart.Format("2006-01-02T15:04:05Z"),
				"endAt":   windowEnd.Format("2006-01-02T15:04:05Z"),
			},
			"riderAccountId":   entry.AccountID,
			"travelerRef":      traveler,
			"estimatedFareRef": "fare-est-" + suffix,
			"intentFingerprint": "ride:" + traveler + ":" + suffix,
		}, nil, []int{200, 201}, "ride-create")
	if err != nil {
		return "", err
	}

	rideID := getString(ride, "rideRequestId")
	branches := p.CtxMap("ride_branches", map[string]float64{
		"complete": 0.75, "driver_cancel_reassign": 0.10,
		"user_cancel": 0.10, "no_show": 0.05,
	})
	branch := WeightedChoice(p.Rng, branches)

	if branch == "user_cancel" {
		_, _, err := p.API.Request(ctx, "POST", "dispatch",
			"/api/v1/ride-requests/"+url.PathEscape(rideID)+"/user-cancel",
			map[string]interface{}{"reason": "CUSTOMER_CHANGED_PLANS"},
			nil, []int{200}, "ride-user-cancel")
		if err != nil {
			return "", err
		}
		MaybeReadProbe(ctx, p, ProbeRefs{RideRequest: rideID, RideRider: entry.AccountID})
		return "user_cancelled", nil
	}

	// Enqueue staff dispatch
	work := NewWorkItem("dispatch")
	work.Ride = rideID
	work.Branch = branch
	select {
	case p.Reg.QDispatch <- work:
	case <-ctx.Done():
		return "", ctx.Err()
	}

	if err := waitForResult(ctx, work, "dispatch", p.Cfg.BehaviorFloat("staff_wait_seconds", 90)); err != nil {
		return "", err
	}

	outcome, _ := work.GetResult("dispatch")
	outcomeStr, _ := outcome.(string)
	if outcomeStr == "" {
		outcomeStr = "completed"
	}
	MaybeReadProbe(ctx, p, ProbeRefs{RideRequest: rideID, RideRider: entry.AccountID})
	return outcomeStr, nil
}
