package main

import (
	"context"
	"fmt"
	"math/rand"
	"net/url"
	"sync"
	"time"
)

// Bootstrap ensures searchable inventory exists (ops-side, idempotent). rec is
// nil-safe; it receives one record per route it probes.
func Bootstrap(ctx context.Context, cfg *Config, api *ApiClient, reg *Registry, rng *rand.Rand,
	rec *Recorder) error {
	if !cfg.Bootstrap.Enabled {
		return nil
	}

	// List existing places, ALL of them.
	//
	// Paginated, not a single limit=100 call: the topology alone is over 60
	// stations, and the transfer journey and the e2e scripts create places
	// too. place-network clamps limit to 100 (ListPlaces in
	// services/place-network/internal/application/service.go) and puts no
	// uniqueness constraint on `code`, so a station that exists beyond the
	// first page would look absent and be created a second time -- silently,
	// with the registry then pointing at the duplicate.
	byCode := make(map[string]map[string]interface{})
	var itemsRaw []interface{}
	for offset := 0; ; {
		_, listing, err := api.Request(ctx, "GET", "place-network",
			fmt.Sprintf("/api/v1/places?limit=100&offset=%d&status=ACTIVE", offset),
			nil, nil, []int{200}, "list-places")
		if err != nil {
			return err
		}
		page, _ := listing["items"].([]interface{})
		if len(page) == 0 {
			break
		}
		itemsRaw = append(itemsRaw, page...)
		for _, raw := range page {
			p, _ := raw.(map[string]interface{})
			if code := getString(p, "code"); code != "" {
				byCode[code] = p
			}
		}
		offset += len(page)
		// A short page is the last page. Checked before `total`, which falls
		// back to -1 rather than 0 so a response that omits the field keeps
		// paging until a short page instead of stopping after the first one.
		if len(page) < 100 {
			break
		}
		if total := getInt(listing, "total", -1); total >= 0 && offset >= total {
			break
		}
	}

	lines, err := ActiveLines(cfg.Bootstrap.Lines)
	if err != nil {
		return err
	}

	places := make(map[string]string)
	nodes := make(map[string]string)

	// One place and one transport node per station of the active lines. These
	// are STATION places, not CITY: they are real stations on a line, and
	// place-network accepts CITY, STATION, AIRPORT and PORT
	// (validPlaceTypeForAPI in services/place-network/internal/application/
	// service.go).
	for _, station := range Stations(lines) {
		if existing, ok := byCode[station.Code]; ok {
			places[station.Code] = getString(existing, "placeId")
		} else {
			_, created, err := api.Request(ctx, "POST", "place-network", "/api/v1/places",
				map[string]interface{}{
					"canonicalName": station.Name,
					"placeType":     "STATION",
					"code":          station.Code,
					"timezone":      "Asia/Shanghai",
				}, nil, []int{200, 201}, "create-place")
			if err != nil {
				return err
			}
			places[station.Code] = getString(created, "placeId")
		}
		reg.mu.Lock()
		reg.Places[station.Code] = places[station.Code]
		reg.mu.Unlock()

		_, n, err := api.Request(ctx, "POST", "place-network", "/api/v1/transport-nodes",
			map[string]interface{}{
				"placeId":      places[station.Code],
				"displayName":  station.Name,
				"servingModes": []string{"RAIL"},
			}, nil, []int{200, 201}, "create-node")
		if err != nil {
			return err
		}
		nodeID := getString(n, "nodeId")
		if nodeID == "" {
			nodeID = getString(n, "transportNodeId")
		}
		nodes[station.Code] = nodeID
	}

	// Build known set
	known := make(map[string]bool)
	reg.mu.Lock()
	for _, r := range reg.Routes {
		known[r.OriginPlace+"|"+r.DestPlace+"|"+r.Date] = true
	}
	reg.mu.Unlock()

	base := cfg.Bootstrap.ServiceNumBase
	speedByLine := make(map[string]int, len(lines))
	for _, line := range lines {
		speedByLine[line.Name] = line.AvgSpeedKMH
	}

	seq := 0
	dates := ComputeDepartureDates(cfg)
	for _, date := range dates {
		// Station pairs spread across the distance spectrum of every active
		// line, so each persona's preferred distance band has inventory. See
		// InventoryPairs for why an even stride and not a uniform draw.
		for _, pair := range InventoryPairs(lines, cfg.Bootstrap.ServicesPerDate, rng) {
			a, b := pair.OriginCode, pair.DestCode
			if nodes[a] == "" || nodes[b] == "" {
				continue
			}
			key := places[a] + "|" + places[b] + "|" + date
			seq++
			if known[key] {
				continue
			}

			reg.mu.Lock()
			number := fmt.Sprintf("G%d", base+len(reg.Routes)+seq)
			reg.mu.Unlock()

			// Departure and arrival follow the trip: a departure somewhere in
			// the service day, plus the running time this distance takes at
			// the line's speed and the dwell at each intermediate stop. The
			// previous code drew an arrival hour at random, so a 131 km hop
			// and the full 1318 km run were scheduled to take the same time
			// and neither matched its distance.
			depHour := rng.Intn(16) + 6
			depMinute := rng.Intn(12) * 5
			departure := mustDate(date).Add(
				time.Duration(depHour)*time.Hour + time.Duration(depMinute)*time.Minute)
			arrival := departure.Add(
				time.Duration(pair.DurationMinutes(speedByLine[pair.Line])) * time.Minute)
			dep := departure.Format("2006-01-02T15:04:05Z")
			arr := arrival.Format("2006-01-02T15:04:05Z")

			_, ss, err := api.Request(ctx, "POST", "service-plan", "/api/v1/scheduled-services",
				map[string]interface{}{
					"carrierId":         "car-" + UUID7(),
					"serviceNumber":     number,
					"departureTime":     dep,
					"arrivalTime":       arr,
					"originNodeId":      nodes[a],
					"destinationNodeId": nodes[b],
				}, nil, []int{200, 201}, "create-service")
			if err != nil {
				continue
			}
			ssID := getString(ss, "scheduledServiceRef")
			if ssID == "" {
				ssID = getString(ss, "scheduledServiceId")
			}

			// One segment, spanning the whole service. service-plan refuses any
			// segment whose endpoints are not exactly its parent service's
			// (serviceViewHasSegment), so the intermediate stops of this trip
			// cannot be expressed as bookable sub-segments. They are real in
			// the topology and they lengthen this service's journey time; they
			// are not sent, because no field accepts them.
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
				Line:          pair.Line,
				DistanceKM:    pair.DistanceKM,
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
	if len(allRoutes) > 0 {
		_, traveler, err := api.Request(ctx, "POST", "traveler-profile", "/api/v1/travelers",
			map[string]interface{}{"accountId": "acc-" + UUID7(), "travelerType": "ADULT", "givenName": "Route", "familyName": "Verifier"}, nil, []int{201}, "bootstrap-traveler")
		if err != nil {
			return err
		}
		probeTvl = getString(traveler, "travelerId")
		if probeTvl == "" {
			return fmt.Errorf("bootstrap traveler response has no travelerId")
		}
	}
	var verification sync.WaitGroup
	var verifiedMu sync.Mutex
	verificationSlots := make(chan struct{}, 8)

	for _, route := range allRoutes {
		select {
		case verificationSlots <- struct{}{}:
		case <-ctx.Done():
			verification.Wait()
			return ctx.Err()
		}
		verification.Add(1)
		go func(route *RouteEntry) {
			defer verification.Done()
			defer func() { <-verificationSlots }()
			// One attempt per route probe, nested under the bootstrap attempt.
			// A dropped route is why later purchases fail on available-train, and
			// which route was dropped is the fact that makes the drop actionable,
			// so it belongs on a record and not in an aggregate count.
			probe := NewAttempt("bootstrap", "bootstrap_verify_route", "")
			probeCtx := WithAttempt(ctx, probe)

			okRoute := false
			// The last thing that went wrong across the three tries. A probe that
			// exhausts its retries must record WHY, and each retry discards its
			// own error to try again.
			var lastErr error
			for attempt := 0; attempt < 3; attempt++ {
				_, res, err := api.Request(probeCtx, "POST", "trip-planning", "/api/v1/itineraries/search",
					map[string]interface{}{
						"originRef":      route.OriginPlace,
						"destinationRef": route.DestPlace,
						"departureDate":  route.Date,
						"travelerRefs":   []string{probeTvl},
						"channel":        "WEB",
					}, nil, []int{200}, "bootstrap-verify")
				if err != nil {
					lastErr = err
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
				// The search answered 200 with nothing bookable. No error to
				// propagate, and the most important outcome in this loop, so it is
				// constructed rather than inferred.
				lastErr = &StepError{
					Step: "bootstrap-verify",
					Detail: fmt.Sprintf("no bookable itinerary for route %s %s->%s %s",
						route.ServiceNumber, truncate(route.OriginPlace, 16),
						truncate(route.DestPlace, 16), route.Date),
					Kind: FailureUnavailable,
				}
				time.Sleep(5 * time.Second)
			}
			if okRoute {
				verifiedMu.Lock()
				verified = append(verified, route)
				verifiedMu.Unlock()
				probe.Record(rec, "bookable", nil)
			} else {
				probe.Record(rec, "", lastErr)
			}
		}(route)
	}
	verification.Wait()

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

	// The bookable-route count is the caller's bootstrap_inventory record
	// outcome, so it is not also printed here.
	return nil
}

// SchedulePublisher periodically ensures services for the rolling window. rec
// is nil-safe; it receives one record per publishing round.
func SchedulePublisher(ctx context.Context, cfg *Config, api *ApiClient, reg *Registry,
	rng *rand.Rand, rec *Recorder) {
	if !cfg.Bootstrap.Enabled {
		return
	}
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Hour):
		}

		// One attempt per hourly round. What the printf carried was the route
		// count after publishing, which is the outcome of this attempt, and a
		// round that publishes nothing is the reason later searches find
		// nothing.
		attempt := NewAttempt("schedule", "schedule_publish", "")
		roundCtx := WithAttempt(ctx, attempt)

		lines, err := ActiveLines(cfg.Bootstrap.Lines)
		if err != nil {
			attempt.Record(rec, "", &StepError{
				Step: "schedule-publish", Detail: err.Error(), Kind: FailureInternal,
			})
			continue
		}
		speedByLine := make(map[string]int, len(lines))
		for _, line := range lines {
			speedByLine[line.Name] = line.AvgSpeedKMH
		}

		dates := ComputeDepartureDates(cfg)
		reg.mu.Lock()
		places := make(map[string]string)
		for k, v := range reg.Places {
			places[k] = v
		}
		known := make(map[string]bool)
		for _, r := range reg.Routes {
			known[r.OriginPlace+"|"+r.DestPlace+"|"+r.Date] = true
		}
		reg.mu.Unlock()

		if len(places) < 2 {
			// Fewer than two places means no pair to schedule between, and
			// every later search will fail on available-train. The round still
			// records, so the cause is in the stream rather than absent.
			attempt.Record(rec, "", &StepError{
				Step:   "schedule-publish",
				Detail: "fewer than 2 places in registry",
				Kind:   FailureUnavailable,
			})
			continue
		}

		// Nodes for the stations of the active lines that this run has places
		// for. The publisher runs hourly against a registry Bootstrap already
		// populated, so a station missing here is one whose place creation
		// failed and which therefore has nothing to schedule.
		nodes := make(map[string]string)
		for _, station := range Stations(lines) {
			if places[station.Code] == "" {
				continue
			}
			_, n, err := api.Request(roundCtx, "POST", "place-network", "/api/v1/transport-nodes",
				map[string]interface{}{
					"placeId":      places[station.Code],
					"displayName":  station.Name,
					"servingModes": []string{"RAIL"},
				}, nil, []int{200, 201}, "schedule-node")
			if err != nil {
				continue
			}
			nodeID := getString(n, "nodeId")
			if nodeID == "" {
				nodeID = getString(n, "transportNodeId")
			}
			nodes[station.Code] = nodeID
		}

		base := cfg.Bootstrap.ServiceNumBase
		seq := 0
		for _, date := range dates {
			for _, pair := range InventoryPairs(lines, cfg.Bootstrap.ServicesPerDate, rng) {
				a, b := pair.OriginCode, pair.DestCode
				if nodes[a] == "" || nodes[b] == "" {
					continue
				}
				key := places[a] + "|" + places[b] + "|" + date
				seq++
				if known[key] {
					continue
				}
				reg.mu.Lock()
				number := fmt.Sprintf("G%d", base+len(reg.Routes)+seq)
				reg.mu.Unlock()

				depHour := rng.Intn(16) + 6
				depMinute := rng.Intn(12) * 5
				departure := mustDate(date).Add(
					time.Duration(depHour)*time.Hour + time.Duration(depMinute)*time.Minute)
				arrival := departure.Add(
					time.Duration(pair.DurationMinutes(speedByLine[pair.Line])) * time.Minute)
				dep := departure.Format("2006-01-02T15:04:05Z")
				arr := arrival.Format("2006-01-02T15:04:05Z")

				_, ss, err := api.Request(roundCtx, "POST", "service-plan", "/api/v1/scheduled-services",
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
				api.Request(roundCtx, "POST", "service-plan", "/api/v1/service-segments",
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
					Line:          pair.Line,
					DistanceKM:    pair.DistanceKM,
				})
				reg.mu.Unlock()
				known[key] = true
			}
		}
		reg.mu.Lock()
		routeCount := len(reg.Routes)
		reg.mu.Unlock()
		attempt.Record(rec, fmt.Sprintf("published_%d_routes", routeCount), nil)
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

// OpsWorker runs the low-frequency operations simulator. rec is nil-safe.
func OpsWorker(ctx context.Context, cfg *Config, api *ApiClient, reg *Registry, stats *Stats,
	rng *rand.Rand, rec *Recorder) {
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

		attempt := NewAttempt("ops", "ops_sweep", "")
		err := opsSweep(WithAttempt(ctx, attempt), cfg, api, reg, stats, rng)
		attempt.Record(rec, "swept", err)
		if err != nil {
			stats.RecordJourney("ops:failed")
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
					"accountId":       acct.AccountID,
					"benefitType":     "BALANCE",
					"balanceType":     "PROMOTION_CREDIT",
					"amount":          map[string]interface{}{"currency": currency, "minorUnits": amount},
					"issuanceSource":  "MANUAL_OPS",
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
		// Derive the suffix from the RANDOM tail of the UUID7, not the leading
		// timestamp. supplier-catalog uniquely indexes lower(profile) (i.e.
		// supplierCode), lower(code) and lower(contractNo); UUID7's leading hex
		// is the millisecond clock, so UUID7()[:8] is constant for ~65s and its
		// first 5 chars for ~3 days, which made these codes collide with 409.
		// Uses the random tail, not the leading timestamp bytes.
		suffix := opsCodeSuffix()
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
					"supplierId":    supplierID,
					"name":          "Loadgen Carrier " + suffix,
					"code":          "LGC" + suffix,
					"transportMode": "RAIL",
				}, nil, []int{201}, "ops-carrier-create")
			if carrier != nil {
				carrierID := getString(carrier, "carrierId")
				reg.RememberOpsEntity("carriers", carrierID)
				stats.RecordJourney("ops:carrier:create")

				_, contract, _ := api.Request(ctx, "POST", "supplier-catalog", "/api/v1/contracts",
					map[string]interface{}{
						"supplierId":    supplierID,
						"carrierId":     carrierID,
						"contractRef":   "LG-CONTRACT-" + suffix,
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

	// Drafting a campaign is ops-side work, so it runs on this sweep and not
	// as a customer journey.
	//
	// It was in `journey_mix` before, which the personas replace, so it had
	// weight zero and marketing-campaign received nothing at all. A persona
	// weight would have been the wrong repair: no customer drafts a campaign,
	// and putting it in the customer mix would have made its rate follow
	// target_rps.
	if rng.Float64() < cfg.Ops.PCampaignDraft {
		if _, err := JourneyCampaign(ctx, opsProviders(cfg, api, reg, stats, rng)); err == nil {
			stats.RecordJourney("ops:campaign:draft")
		} else {
			stats.RecordJourney("ops:campaign:failed")
		}
	}
	return nil
}

// opsProviders builds the Providers a journey needs, for the ops sweep to
// reuse a journey written against that type.
//
// The behavior context is the config's own `behavior` block with no persona
// merged in: an ops action has no persona, and ApplyPersona would replace the
// journey mix this sweep does not use anyway.
func opsProviders(cfg *Config, api *ApiClient, reg *Registry, stats *Stats,
	rng *rand.Rand) *Providers {
	p := NewProviders(cfg, api, reg, stats, rng)
	p.ApplyPersona("", nil)
	return p
}
