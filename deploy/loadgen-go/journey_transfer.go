package main

import (
	"context"
	"net/url"
	"time"
)

// JourneyTransfer performs a transfer management drill.
func JourneyTransfer(ctx context.Context, p *Providers) (string, error) {
	suffix := UUID7()
	now := time.Now().UTC()

	// Create MCT rule
	_, rule, err := p.API.Request(ctx, "POST", "transfer-management", "/api/v1/mct-rules",
		map[string]interface{}{
			"fromNodeType":      "STATION",
			"toNodeType":        "STATION",
			"transferCategory":  "SAME_STATION",
			"minimumMinutes":    20,
			"conditions":        map[string]interface{}{"loadgen": true},
			"validFrom":         ISO(now.Add(-24 * time.Hour)),
		}, nil, []int{201}, "transfer-mct-create")
	if err != nil {
		return "", err
	}
	ruleID := getString(rule, "mctRuleId")

	// Publish MCT rule
	_, _, err = p.API.Request(ctx, "POST", "transfer-management",
		"/api/v1/mct-rules/"+url.PathEscape(ruleID)+"/publish",
		map[string]interface{}{
			"publishedBy":   map[string]interface{}{"actorType": "OPERATIONS", "actorId": "loadgen"},
			"publishReason": "loadgen",
		}, nil, []int{200}, "transfer-mct-publish")
	if err != nil {
		return "", err
	}

	orderID := "jo-lg-" + suffix

	// Create transfer plan
	_, plan, err := p.API.Request(ctx, "POST", "transfer-management", "/api/v1/transfer-plans",
		map[string]interface{}{
			"itineraryRef":            "iti-lg-" + suffix,
			"planningSnapshotVersion": 1,
			"journeyOrderId":          orderID,
			"travelerRefs":            []string{"trav-lg-" + suffix},
		}, nil, []int{201}, "transfer-plan")
	if err != nil {
		return "", err
	}
	planID := getString(plan, "transferPlanId")

	// Choose contract type
	contractMix := p.CtxMap("transfer_contract_mix", map[string]float64{
		"PROTECTED": 0.55, "SELF_TRANSFER": 0.45,
	})
	contractType := WeightedChoice(p.Rng, contractMix)

	// Create connection
	_, conn, err := p.API.Request(ctx, "POST", "transfer-management", "/api/v1/connections",
		map[string]interface{}{
			"transferPlanId":     planID,
			"itineraryRef":       "iti-lg-" + suffix,
			"journeyOrderId":    orderID,
			"previousSegmentRef": "seg-lg-prev-" + suffix,
			"nextSegmentRef":     "seg-lg-next-" + suffix,
			"travelerRefs":       []string{"trav-lg-" + suffix},
			"fromNodeRef":        "sta-lg-a",
			"toNodeRef":          "sta-lg-a",
			"fromNodeType":       "STATION",
			"toNodeType":         "STATION",
			"transferCategory":   "SAME_STATION",
			"contractId":         "cct-lg-" + suffix,
			"contractType":       contractType,
			"window": map[string]interface{}{
				"plannedArrivalAt": ISO(now),
				"nextDepartureAt":  ISO(now.Add(60 * time.Minute)),
				"nextCutoffAt":     ISO(now.Add(50 * time.Minute)),
			},
		}, nil, []int{201}, "transfer-connection")
	if err != nil {
		return "", err
	}

	outcome := lower(getString(conn, "status"))
	if outcome == "" {
		outcome = "planned"
	}

	// Optionally simulate delay
	if p.Rng.Float64() < p.CtxFloat("p_transfer_delay", 0.35) {
		miss := p.Rng.Float64() < p.CtxFloat("p_transfer_missed", 0.25)
		var eta time.Time
		if miss {
			eta = now.Add(80 * time.Minute)
		} else {
			eta = now.Add(40 * time.Minute)
		}

		if p.Rng.Float64() < 0.10 {
			_, result, _ := p.API.Request(ctx, "POST", "transfer-management",
				"/api/v1/segment-status-reports",
				map[string]interface{}{
					"segmentRef":  "seg-lg-prev-" + suffix,
					"reportType":  "DELAY",
					"reportedBy":  map[string]interface{}{"actorType": "SYSTEM", "actorId": "loadgen"},
					"sourceSystem":   "OPERATIONS",
					"sourceRecordId": "lg-transfer-" + suffix,
					"observedAt":     NowISO(),
					"estimatedArrivalAt": ISO(eta),
				}, nil, []int{202}, "transfer-report-ops")
			if result != nil {
				updatedRaw, _ := result["updatedConnections"].([]interface{})
				if len(updatedRaw) > 0 {
					updated, _ := updatedRaw[0].(map[string]interface{})
					if s := getString(updated, "status"); s != "" {
						outcome = lower(s)
					}
				}
			}
		} else {
			p.API.Request(ctx, "POST", "fulfillment", "/api/v1/segment-status",
				map[string]interface{}{
					"segmentRef":           "seg-lg-prev-" + suffix,
					"scheduledServiceRef":  "svc-lg-" + suffix,
					"serviceDate":          now.Format("2006-01-02"),
					"status":               "DELAY",
					"sourceSystem":         "OPS",
					"observedAt":           NowISO(),
					"estimatedArrivalAt":   ISO(eta),
				}, nil, []int{201}, "fulfillment-transfer-report")
			outcome = "event_reported"
		}
	}

	return outcome, nil
}
