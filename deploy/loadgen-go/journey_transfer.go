package main

import (
	"context"
	"net/url"
	"time"
)

// transferStationNode returns a real place-network transport node usable as a
// transfer connection endpoint, seeding it once per process on first use.
//
// transfer-management's register_connection resolves fromNodeRef/toNodeRef via
// place-network GET /api/v1/transport-nodes/{id} and turns a 404 into a 422, so
// the refs must be genuine nodeIds. It also cross-checks the owning place's
// placeType against the declared node type (STATION -> placeType STATION), so
// the node must hang off a STATION place -- the CITY places seeded by Bootstrap
// for route inventory are not usable here. This mirrors deploy/e2e/19-transfer.sh.
func transferStationNode(ctx context.Context, p *Providers) (string, error) {
	p.Reg.transferStationMu.Lock()
	defer p.Reg.transferStationMu.Unlock()

	if p.Reg.transferStationNode != "" {
		return p.Reg.transferStationNode, nil
	}

	suffix := UUID7()
	// place-network codes are short; keep it unique but bounded.
	code := "TML" + suffix[len(suffix)-9:]
	_, place, err := p.API.Request(ctx, "POST", "place-network", "/api/v1/places",
		map[string]interface{}{
			"placeType":     "STATION",
			"canonicalName": "Loadgen Transfer " + suffix[len(suffix)-12:] + " station",
			"code":          code,
			"timezone":      "UTC",
		}, nil, []int{200, 201}, "transfer-seed-place")
	if err != nil {
		return "", err
	}
	placeID := getString(place, "placeId")

	_, node, err := p.API.Request(ctx, "POST", "place-network", "/api/v1/transport-nodes",
		map[string]interface{}{
			"placeId":      placeID,
			"displayName":  "Loadgen Transfer node",
			"servingModes": []string{"RAIL"},
		}, nil, []int{200, 201}, "transfer-seed-node")
	if err != nil {
		return "", err
	}
	nodeID := getString(node, "nodeId")
	if nodeID == "" {
		nodeID = getString(node, "transportNodeId")
	}
	if nodeID == "" {
		return "", &StepError{Step: "transfer-seed-node", Detail: "place-network returned no nodeId"}
	}

	p.Reg.transferStationNode = nodeID
	return nodeID, nil
}

// JourneyTransfer performs a transfer management drill.
func JourneyTransfer(ctx context.Context, p *Providers) (string, error) {
	suffix := UUID7()
	now := time.Now().UTC()

	// Resolve a real place-network node for the connection endpoints.
	stationNode, err := transferStationNode(ctx, p)
	if err != nil {
		return "", err
	}

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
			"fromNodeRef":        stationNode,
			"toNodeRef":          stationNode,
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

	MaybeReadProbe(ctx, p, ProbeRefs{
		TransferPlan:       planID,
		TransferConnection: getString(conn, "connectionId"),
		TransferJourney:    orderID,
	})

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
