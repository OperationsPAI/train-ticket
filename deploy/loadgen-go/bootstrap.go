package main

import (
	"context"
	"fmt"
	"math/rand"
	"net/url"
	"time"
)

// Bootstrap ensures searchable inventory exists (ops-side, idempotent).
func Bootstrap(ctx context.Context, cfg *Config, api *ApiClient, reg *Registry, rng *rand.Rand) error {
	if !cfg.Bootstrap.Enabled {
		return nil
	}

	// List existing places
	_, listing, err := api.Request(ctx, "GET", "place-network",
		"/api/v1/places?limit=100&offset=0&status=ACTIVE",
		nil, nil, []int{200}, "list-places")
	if err != nil {
		return err
	}

	byCode := make(map[string]map[string]interface{})
	itemsRaw, _ := listing["items"].([]interface{})
	for _, raw := range itemsRaw {
		p, _ := raw.(map[string]interface{})
		if code := getString(p, "code"); code != "" {
			byCode[code] = p
		}
	}

	places := make(map[string]string)
	nodes := make(map[string]string)

	for _, city := range cfg.Bootstrap.Cities {
		if existing, ok := byCode[city.Code]; ok {
			places[city.Code] = getString(existing, "placeId")
		} else {
			_, created, err := api.Request(ctx, "POST", "place-network", "/api/v1/places",
				map[string]interface{}{
					"canonicalName": city.Name,
					"placeType":    "CITY",
					"code":         city.Code,
					"timezone":     "Asia/Shanghai",
				}, nil, []int{200, 201}, "create-place")
			if err != nil {
				return err
			}
			places[city.Code] = getString(created, "placeId")
		}
		reg.mu.Lock()
		reg.Places[city.Code] = places[city.Code]
		reg.mu.Unlock()

		_, n, err := api.Request(ctx, "POST", "place-network", "/api/v1/transport-nodes",
			map[string]interface{}{
				"placeId":      places[city.Code],
				"displayName":  city.Name + " Station",
				"servingModes": []string{"RAIL"},
			}, nil, []int{200, 201}, "create-node")
		if err != nil {
			return err
		}
		nodeID := getString(n, "nodeId")
		if nodeID == "" {
			nodeID = getString(n, "transportNodeId")
		}
		nodes[city.Code] = nodeID
	}

	// Build known set
	known := make(map[string]bool)
	reg.mu.Lock()
	for _, r := range reg.Routes {
		known[r.OriginPlace+"|"+r.DestPlace+"|"+r.Date] = true
	}
	reg.mu.Unlock()

	base := cfg.Bootstrap.ServiceNumBase
	codes := make([]string, 0, len(places))
	for k := range places {
		codes = append(codes, k)
	}

	seq := 0
	dates := ComputeDepartureDates(cfg)
	for _, date := range dates {
		for i := 0; i < cfg.Bootstrap.ServicesPerDate; i++ {
			if len(codes) < 2 {
				break
			}
			idx := rng.Perm(len(codes))
			a, b := codes[idx[0]], codes[idx[1]]
			key := places[a] + "|" + places[b] + "|" + date
			seq++
			if known[key] {
				continue
			}

			reg.mu.Lock()
			number := fmt.Sprintf("G%d", base+len(reg.Routes)+seq)
			reg.mu.Unlock()

			depHour := rng.Intn(12) + 6
			arrHour := rng.Intn(4) + 19
			dep := fmt.Sprintf("%sT%02d:00:00Z", date, depHour)
			arr := fmt.Sprintf("%sT%02d:30:00Z", date, arrHour)

			_, ss, err := api.Request(ctx, "POST", "service-plan", "/api/v1/scheduled-services",
				map[string]interface{}{
					"carrierId":       "car-" + UUID7(),
					"serviceNumber":   number,
					"departureTime":   dep,
					"arrivalTime":     arr,
					"originNodeId":    nodes[a],
					"destinationNodeId": nodes[b],
				}, nil, []int{200, 201}, "create-service")
			if err != nil {
				continue
			}
			ssID := getString(ss, "scheduledServiceRef")
			if ssID == "" {
				ssID = getString(ss, "scheduledServiceId")
			}

			_, _, _ = api.Request(ctx, "POST", "service-plan", "/api/v1/service-segments",
				map[string]interface{}{
					"scheduledServiceRef": ssID,
					"originStopRef":       nodes[a],
					"destinationStopRef":  nodes[b],
					"departureTime":       dep,
					"arrivalTime":         arr,
				}, nil, []int{200, 201}, "create-segment")

			reg.mu.Lock()
			reg.Routes = append(reg.Routes, &RouteEntry{
				OriginPlace:   places[a],
				DestPlace:     places[b],
				Date:          date,
				ServiceNumber: number,
			})
			reg.mu.Unlock()
			known[key] = true
		}
	}

	// Verify routes are actually bookable
	var probeTvl string
	var verified []*RouteEntry
	reg.mu.Lock()
	allRoutes := make([]*RouteEntry, len(reg.Routes))
	copy(allRoutes, reg.Routes)
	reg.mu.Unlock()

	for _, route := range allRoutes {
		okRoute := false
		for attempt := 0; attempt < 3; attempt++ {
			if probeTvl == "" {
				_, t, err := api.Request(ctx, "POST", "traveler-profile", "/api/v1/travelers",
					map[string]interface{}{
						"accountId":    "acc-" + UUID7(),
						"travelerType": "ADULT",
						"givenName":    "Boot",
						"familyName":   "Strap",
					}, nil, []int{200, 201}, "bootstrap-traveler")
				if err == nil {
					probeTvl = getString(t, "travelerId")
				}
			}
			if probeTvl == "" {
				time.Sleep(5 * time.Second)
				continue
			}

			_, res, err := api.Request(ctx, "POST", "trip-planning", "/api/v1/itineraries/search",
				map[string]interface{}{
					"originRef":      route.OriginPlace,
					"destinationRef": route.DestPlace,
					"departureDate":  route.Date,
					"travelerRefs":   []string{probeTvl},
					"channel":        "WEB",
				}, nil, []int{200}, "bootstrap-verify")
			if err != nil {
				time.Sleep(5 * time.Second)
				continue
			}

			itins := getBookableItineraries(res)
			if len(itins) > 0 {
				leg := getFirstLeg(itins[0])
				route.ScheduledService = getString(leg, "servicePlanRef")
				route.OriginNode = getString(leg, "originStopRef")
				route.DestNode = getString(leg, "destinationStopRef")
				okRoute = true
				break
			}
			time.Sleep(5 * time.Second)
		}
		if okRoute {
			verified = append(verified, route)
		} else {
			fmt.Printf("[bootstrap] dropping unbookable route %s %s->%s %s\n",
				route.ServiceNumber, truncate(route.OriginPlace, 16),
				truncate(route.DestPlace, 16), route.Date)
		}
	}

	reg.mu.Lock()
	reg.Routes = verified
	reg.mu.Unlock()

	// Discovery sweep for additional bookable segments
	reg.mu.Lock()
	routeCount := len(reg.Routes)
	reg.mu.Unlock()
	minRoutes := cfg.Bootstrap.MinRoutes
	if minRoutes == 0 {
		minRoutes = 2
	}

	if routeCount < minRoutes {
		allPlaces := make([]string, 0)
		for _, raw := range itemsRaw {
			p, _ := raw.(map[string]interface{})
			if pid := getString(p, "placeId"); pid != "" {
				allPlaces = append(allPlaces, pid)
			}
		}
		seen := make(map[string]bool)
		reg.mu.Lock()
		for _, r := range reg.Routes {
			seen[r.OriginPlace+"|"+r.DestPlace+"|"+r.Date] = true
		}
		reg.mu.Unlock()

		for _, date := range dates {
			probes := 0
			for _, aPlace := range allPlaces {
				for _, bPlace := range allPlaces {
					if aPlace == bPlace || probes >= 12 {
						continue
					}
					if seen[aPlace+"|"+bPlace+"|"+date] {
						continue
					}
					probes++
					_, res, err := api.Request(ctx, "POST", "trip-planning", "/api/v1/itineraries/search",
						map[string]interface{}{
							"originRef":      aPlace,
							"destinationRef": bPlace,
							"departureDate":  date,
							"travelerRefs":   []string{probeTvl},
							"channel":        "WEB",
						}, nil, []int{200}, "bootstrap-discover")
					if err != nil {
						continue
					}
					itins := getBookableItineraries(res)
					if len(itins) > 0 {
						leg := getFirstLeg(itins[0])
						reg.mu.Lock()
						reg.Routes = append(reg.Routes, &RouteEntry{
							OriginPlace:      aPlace,
							DestPlace:        bPlace,
							Date:             date,
							ScheduledService: getString(leg, "servicePlanRef"),
							OriginNode:       getString(leg, "originStopRef"),
							DestNode:         getString(leg, "destinationStopRef"),
						})
						reg.mu.Unlock()
						seen[aPlace+"|"+bPlace+"|"+date] = true
					}
				}
			}
		}
	}

	reg.mu.Lock()
	fmt.Printf("[bootstrap] routes known (bookable): %d\n", len(reg.Routes))
	reg.mu.Unlock()
	return nil
}

// SchedulePublisher periodically ensures services for the rolling window.
func SchedulePublisher(ctx context.Context, cfg *Config, api *ApiClient, reg *Registry, rng *rand.Rand) {
	if !cfg.Bootstrap.Enabled {
		return
	}
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Hour):
		}

		dates := ComputeDepartureDates(cfg)
		reg.mu.Lock()
		codes := make([]string, 0, len(reg.Places))
		for k := range reg.Places {
			codes = append(codes, k)
		}
		places := make(map[string]string)
		for k, v := range reg.Places {
			places[k] = v
		}
		known := make(map[string]bool)
		for _, r := range reg.Routes {
			known[r.OriginPlace+"|"+r.DestPlace+"|"+r.Date] = true
		}
		reg.mu.Unlock()

		if len(codes) < 2 {
			continue
		}

		nodes := make(map[string]string)
		for _, code := range codes {
			_, n, err := api.Request(ctx, "POST", "place-network", "/api/v1/transport-nodes",
				map[string]interface{}{
					"placeId":      places[code],
					"displayName":  code + " Station",
					"servingModes": []string{"RAIL"},
				}, nil, []int{200, 201}, "schedule-node")
			if err != nil {
				continue
			}
			nodeID := getString(n, "nodeId")
			if nodeID == "" {
				nodeID = getString(n, "transportNodeId")
			}
			nodes[code] = nodeID
		}

		base := cfg.Bootstrap.ServiceNumBase
		seq := 0
		for _, date := range dates {
			for i := 0; i < cfg.Bootstrap.ServicesPerDate; i++ {
				idx := rng.Perm(len(codes))
				a, b := codes[idx[0]], codes[idx[1]]
				key := places[a] + "|" + places[b] + "|" + date
				seq++
				if known[key] {
					continue
				}
				reg.mu.Lock()
				number := fmt.Sprintf("G%d", base+len(reg.Routes)+seq)
				reg.mu.Unlock()

				depHour := rng.Intn(12) + 6
				arrHour := rng.Intn(4) + 19
				dep := fmt.Sprintf("%sT%02d:00:00Z", date, depHour)
				arr := fmt.Sprintf("%sT%02d:30:00Z", date, arrHour)

				_, ss, err := api.Request(ctx, "POST", "service-plan", "/api/v1/scheduled-services",
					map[string]interface{}{
						"carrierId":         "car-" + UUID7(),
						"serviceNumber":     number,
						"departureTime":     dep,
						"arrivalTime":       arr,
						"originNodeId":      nodes[a],
						"destinationNodeId": nodes[b],
					}, nil, []int{200, 201}, "schedule-service")
				if err != nil {
					continue
				}
				ssID := getString(ss, "scheduledServiceRef")
				if ssID == "" {
					ssID = getString(ss, "scheduledServiceId")
				}
				api.Request(ctx, "POST", "service-plan", "/api/v1/service-segments",
					map[string]interface{}{
						"scheduledServiceRef": ssID,
						"originStopRef":       nodes[a],
						"destinationStopRef":  nodes[b],
						"departureTime":       dep,
						"arrivalTime":         arr,
					}, nil, []int{200, 201}, "schedule-segment")

				reg.mu.Lock()
				reg.Routes = append(reg.Routes, &RouteEntry{
					OriginPlace:   places[a],
					DestPlace:     places[b],
					Date:          date,
					ServiceNumber: number,
				})
				reg.mu.Unlock()
				known[key] = true
			}
		}
		reg.mu.Lock()
		fmt.Printf("[schedule-publisher] routes: %d\n", len(reg.Routes))
		reg.mu.Unlock()
	}
}

func getFirstLeg(itin map[string]interface{}) map[string]interface{} {
	legsRaw, _ := itin["legs"].([]interface{})
	if len(legsRaw) == 0 {
		return nil
	}
	leg, _ := legsRaw[0].(map[string]interface{})
	return leg
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}

// Think pauses for a random think-time duration.
func Think(ctx context.Context, p *Providers) {
	t := p.Cfg.Run.ThinkTime
	// Check if persona overrides think_time
	if v, ok := p.Ctx["think_time"]; ok {
		if m, ok := v.(map[string]interface{}); ok {
			if min, ok := m["min"].(float64); ok {
				t.Min = min
			}
			if max, ok := m["max"].(float64); ok {
				t.Max = max
			}
		}
	}
	d := time.Duration((t.Min + p.Rng.Float64()*(t.Max-t.Min)) * float64(time.Second))
	select {
	case <-ctx.Done():
	case <-time.After(d):
	}
}

// OpsWorker runs the low-frequency operations simulator.
func OpsWorker(ctx context.Context, cfg *Config, api *ApiClient, reg *Registry, stats *Stats, rng *rand.Rand) {
	if !cfg.Ops.Enabled {
		return
	}
	interval := cfg.Ops.IntervalSeconds
	for {
		d := time.Duration((interval.Min + rng.Float64()*(interval.Max-interval.Min)) * float64(time.Second))
		select {
		case <-ctx.Done():
			return
		case <-time.After(d):
		}

		if err := opsSweep(ctx, cfg, api, reg, stats, rng); err != nil {
			stats.RecordJourney("ops:failed")
			fmt.Printf("[ops] failed - %v\n", err)
		} else {
			stats.RecordJourney("ops:sweep")
		}
	}
}

func opsSweep(ctx context.Context, cfg *Config, api *ApiClient, reg *Registry, stats *Stats, rng *rand.Rand) error {
	// Reporting reads
	_, _, _ = api.Request(ctx, "GET", "reporting",
		"/api/v1/metrics?category=operational&limit=20&offset=0",
		nil, nil, []int{200}, "ops-reporting-metrics")
	stats.RecordJourney("ops:reporting:metrics")

	dashboard := cfg.Ops.DashboardID
	if dashboard == "" {
		dashboard = "dash-revenue"
	}
	_, _, _ = api.Request(ctx, "GET", "reporting",
		"/api/v1/dashboards/"+url.PathEscape(dashboard),
		nil, nil, []int{200}, "ops-reporting-dashboard")
	stats.RecordJourney("ops:reporting:dashboard")

	// Finance reads
	reg.mu.Lock()
	purchases := make([]*Purchase, len(reg.Purchases))
	copy(purchases, reg.Purchases)
	reg.mu.Unlock()

	if len(purchases) > 0 {
		orderID := purchases[rng.Intn(len(purchases))].Order
		_, _, _ = api.Request(ctx, "GET", "finance-settlement",
			"/api/v1/reconciliation-cases?orderId="+url.QueryEscape(orderID)+"&limit=20&offset=0",
			nil, nil, []int{200}, "ops-finance-reconciliation-list")
	} else {
		_, _, _ = api.Request(ctx, "GET", "finance-settlement",
			"/api/v1/reconciliation-cases?limit=20&offset=0",
			nil, nil, []int{200}, "ops-finance-reconciliation-list")
	}
	stats.RecordJourney("ops:finance:reconciliation_cases")

	// Wallet promotion sweep
	if rng.Float64() < cfg.Ops.PWalletManualIssue {
		reg.mu.Lock()
		accounts := make([]*AccountEntry, len(reg.Accounts))
		copy(accounts, reg.Accounts)
		reg.mu.Unlock()
		if len(accounts) > 0 {
			acct := accounts[rng.Intn(len(accounts))]
			amount := cfg.Ops.WalletManualIssueMinorUnits
			if amount == 0 {
				amount = 100
			}
			until := time.Now().UTC().Add(14 * 24 * time.Hour).Format("2006-01-02T15:04:05Z")
			currency := cfg.Currency()
			_, benefit, _ := api.Request(ctx, "POST", "wallet-promotion", "/api/v1/benefits",
				map[string]interface{}{
					"accountId":    acct.AccountID,
					"benefitType":  "BALANCE",
					"balanceType":  "PROMOTION_CREDIT",
					"amount":       map[string]interface{}{"currency": currency, "minorUnits": amount},
					"issuanceSource": "MANUAL_OPS",
					"applicableScope": map[string]interface{}{"scopeType": "ANY_TRIP", "currency": currency},
					"redemptionRule":  map[string]interface{}{"singleUse": false, "requiresReservation": false},
					"revocationRule":  map[string]interface{}{},
					"validFrom":       NowISO(),
					"validUntil":      until,
					"businessReason":  map[string]interface{}{"reasonType": "MANUAL_OPS", "reasonCode": "LOADGEN_MANUAL_OPS", "referenceType": "MANUAL_ACTION", "referenceId": "act-" + UUID7()},
				}, nil, []int{201}, "ops-wallet-issue")
			if benefit != nil {
				stats.RecordJourney("ops:wallet:issued")
				benefitID := getString(benefit, "benefitId")
				api.Request(ctx, "GET", "wallet-promotion", "/api/v1/benefits/"+url.PathEscape(benefitID), nil, nil, []int{200}, "ops-wallet-get-benefit")
				api.Request(ctx, "GET", "wallet-promotion", "/api/v1/wallet-accounts/"+url.PathEscape(acct.AccountID), nil, nil, []int{200}, "ops-wallet-get-account")
			}
		}
	}

	// Supplier catalog sweep
	_, suppliers, _ := api.Request(ctx, "GET", "supplier-catalog",
		"/api/v1/suppliers?limit=20&offset=0", nil, nil, []int{200}, "ops-supplier-list")
	stats.RecordJourney("ops:supplier:list")
	if suppliers != nil {
		supplierItems, _ := suppliers["items"].([]interface{})
		for _, raw := range supplierItems {
			s, _ := raw.(map[string]interface{})
			if sid := getString(s, "supplierId"); sid != "" {
				reg.RememberOpsEntity("suppliers", sid)
				break
			}
		}
	}

	if rng.Float64() < cfg.Ops.PSupplierCatalogWrite {
		suffix := UUID7()[:8]
		_, supplier, _ := api.Request(ctx, "POST", "supplier-catalog", "/api/v1/suppliers",
			map[string]interface{}{
				"legalName":    "Loadgen Rail Supplier " + suffix + " Ltd",
				"brandName":    "LG Rail " + suffix,
				"supplierCode": "LG" + suffix,
			}, nil, []int{201}, "ops-supplier-create")
		if supplier != nil {
			supplierID := getString(supplier, "supplierId")
			reg.RememberOpsEntity("suppliers", supplierID)
			stats.RecordJourney("ops:supplier:create")

			_, carrier, _ := api.Request(ctx, "POST", "supplier-catalog", "/api/v1/carriers",
				map[string]interface{}{
					"supplierId":     supplierID,
					"name":           "Loadgen Carrier " + suffix,
					"code":           "LGC" + suffix[:5],
					"transportMode":  "RAIL",
				}, nil, []int{201}, "ops-carrier-create")
			if carrier != nil {
				carrierID := getString(carrier, "carrierId")
				reg.RememberOpsEntity("carriers", carrierID)
				stats.RecordJourney("ops:carrier:create")

				_, contract, _ := api.Request(ctx, "POST", "supplier-catalog", "/api/v1/contracts",
					map[string]interface{}{
						"supplierId":   supplierID,
						"carrierId":    carrierID,
						"contractRef":  "LG-CONTRACT-" + suffix,
						"effectiveFrom": NowISO(),
					}, nil, []int{201}, "ops-contract-create")
				if contract != nil {
					if cid := getString(contract, "contractId"); cid != "" {
						reg.RememberOpsEntity("contracts", cid)
					}
					stats.RecordJourney("ops:contract:create")
				}
			}
		}
	}
	return nil
}
