package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/rand"
	"net/url"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

// ScalperSim simulates a ticket scalper (zero think time, account rotation, retry storms).
type ScalperSim struct {
	cfg       *Config
	api       *ApiClient
	reg       *Registry
	stats     *Stats
	rng       *rand.Rand
	redis     *redis.Client
	workerIdx int

	accountPool    []*AccountEntry
	accountCursor  int
	targetSegments []*RouteEntry
	currentTarget  int
	identityCache  map[string]map[string]string

	burstGapSeconds    float64
	slowdownProb       float64
	slowdownMinSeconds float64
	slowdownMaxSeconds float64
	ipPool             []string
	ipCursor           int
	currentIP          string
	fingerprints       []map[string]string
}

func NewScalperSim(cfg *Config, api *ApiClient, reg *Registry, stats *Stats, rng *rand.Rand, workerIdx int) *ScalperSim {
	opts, _ := redis.ParseURL(cfg.Target.RedisURL)
	var rdb *redis.Client
	if opts != nil {
		rdb = redis.NewClient(opts)
	}

	sc := cfg.Scalper
	burstGap := sc.BurstGapMs / 1000.0
	if burstGap < 0 {
		burstGap = 0.05
	}
	slowProb := sc.SlowdownProb
	if slowProb == 0 {
		slowProb = 0.12
	}
	slowMin := sc.SlowdownMinMs / 1000.0
	if slowMin == 0 {
		slowMin = 0.25
	}
	slowMax := sc.SlowdownMaxMs / 1000.0
	if slowMax < slowMin {
		slowMax = 1.6
	}

	sim := &ScalperSim{
		cfg:                cfg,
		api:                api,
		reg:                reg,
		stats:              stats,
		rng:                rng,
		redis:              rdb,
		workerIdx:          workerIdx,
		identityCache:      make(map[string]map[string]string),
		burstGapSeconds:    burstGap,
		slowdownProb:       slowProb,
		slowdownMinSeconds: slowMin,
		slowdownMaxSeconds: slowMax,
		ipPool:             buildIPPool(cfg, workerIdx),
		ipCursor:           workerIdx,
	}
	sim.fingerprints = sim.buildFingerprints()
	return sim
}

func buildIPPool(cfg *Config, _ int) []string {
	poolSize := 24
	prefix := "203.0.113"
	start := 10
	pool := make([]string, poolSize)
	for i := range pool {
		pool[i] = fmt.Sprintf("%s.%d", prefix, start+i)
	}
	return pool
}

func (s *ScalperSim) buildFingerprints() []map[string]string {
	languages := []string{"zh-CN,zh;q=0.9", "zh-CN,zh;q=0.8,en;q=0.6", "en-US,en;q=0.7"}
	platforms := []string{`"Windows"`, `"macOS"`, `"Linux"`, `"Android"`, `"iOS"`}
	userAgents := []string{
		"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36",
		"Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 Safari/605.1.15",
		"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
		"Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) Mobile/15E148 Safari/604.1",
		"Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Mobile Safari/537.36",
	}
	poolSize := 12
	fps := make([]map[string]string, poolSize)
	for i := range fps {
		sessionID := UUID7()[:20]
		h := sha256.Sum256([]byte(sessionID))
		fps[i] = map[string]string{
			"User-Agent":         userAgents[(s.workerIdx+i)%len(userAgents)],
			"Accept-Language":    languages[i%len(languages)],
			"Sec-CH-UA-Platform": platforms[i%len(platforms)],
			"Cookie":             fmt.Sprintf("tt_session=%s; tt_fp=%s", sessionID, hex.EncodeToString(h[:8])),
		}
	}
	return fps
}

func (s *ScalperSim) nextHeaders() map[string]string {
	nextIP := s.ipPool[s.ipCursor%len(s.ipPool)]
	s.ipCursor++
	if s.currentIP != "" && s.currentIP != nextIP {
		s.stats.ScalperIPRotations.Add(1)
	}
	s.currentIP = nextIP

	fp := s.fingerprints[s.rng.Intn(len(s.fingerprints))]
	headers := make(map[string]string, len(fp)+2)
	for k, v := range fp {
		headers[k] = v
	}
	nonce := UUID7()[:10]
	headers["X-Forwarded-For"] = nextIP
	headers["X-Client-Trace"] = fmt.Sprintf("sc-%d-%s", s.workerIdx, nonce)
	return headers
}

func (s *ScalperSim) burstPause(ctx context.Context) {
	if s.rng.Float64() < s.slowdownProb {
		d := time.Duration((s.slowdownMinSeconds + s.rng.Float64()*(s.slowdownMaxSeconds-s.slowdownMinSeconds)) * float64(time.Second))
		select {
		case <-ctx.Done():
		case <-time.After(d):
		}
		return
	}
	if s.burstGapSeconds <= 0 {
		return
	}
	d := time.Duration(s.rng.Float64() * s.burstGapSeconds * float64(time.Second))
	select {
	case <-ctx.Done():
	case <-time.After(d):
	}
}

// Prepare registers accounts and pre-verifies identities.
func (s *ScalperSim) Prepare(ctx context.Context) error {
	for i := 0; i < s.cfg.Scalper.AccountsPerWorker; i++ {
		accountID := fmt.Sprintf("acc-scalper-%d-%d-%s", s.workerIdx, i, UUID7())
		_, _, err := s.api.Request(ctx, "POST", "account", "/api/v1/accounts",
			map[string]interface{}{"accountId": accountID},
			s.nextHeaders(), []int{200, 201}, "scalper-register")
		if err != nil {
			return err
		}
		entry := s.reg.AddAccount(accountID)

		given, family := RandName(s.rng)
		_, data, err := s.api.Request(ctx, "POST", "traveler-profile", "/api/v1/travelers",
			map[string]interface{}{
				"accountId":    accountID,
				"travelerType": "ADULT",
				"givenName":    given,
				"familyName":   family,
			}, s.nextHeaders(), []int{200, 201}, "scalper-create-traveler")
		if err != nil {
			return err
		}
		tvl := getString(data, "travelerId")
		entry.Travelers = append(entry.Travelers, tvl)

		idRefs, err := s.ensureIdentityVerified(ctx, tvl)
		if err != nil {
			return err
		}
		s.identityCache[tvl] = idRefs
		s.accountPool = append(s.accountPool, entry)
	}

	// Discover hot segments
	discovered := s.discoverHotSegments(ctx)
	if len(discovered) > 0 {
		s.targetSegments = discovered
	} else {
		routes := s.reg.GetRoutes()
		s.targetSegments = diversifiedSegments(routes, s.cfg.Scalper.TargetSegments)
	}
	return nil
}

func (s *ScalperSim) discoverHotSegments(ctx context.Context) []*RouteEntry {
	s.reg.mu.Lock()
	places := make(map[string]string)
	for k, v := range s.reg.Places {
		places[k] = v
	}
	s.reg.mu.Unlock()

	if len(places) < 2 {
		return nil
	}
	codes := make([]string, 0, len(places))
	for k := range places {
		codes = append(codes, k)
	}

	var segments []*RouteEntry
	type pair struct{ a, b string }
	var pairs []pair
	for _, a := range codes {
		for _, b := range codes {
			if a != b {
				pairs = append(pairs, pair{a, b})
			}
		}
	}
	s.rng.Shuffle(len(pairs), func(i, j int) { pairs[i], pairs[j] = pairs[j], pairs[i] })

	prov := NewProviders(s.cfg, s.api, s.reg, s.stats, s.rng)
	for _, pr := range pairs {
		if len(pairs) > 8 {
			pairs = pairs[:8]
		}
		date := prov.DepartureDate()
		_, data, err := s.api.Request(ctx, "POST", "trip-planning", "/api/v1/itineraries/search",
			map[string]interface{}{
				"originRef":      places[pr.a],
				"destinationRef": places[pr.b],
				"departureDate":  date,
				"travelerRefs":   []string{"tvl-scout-" + UUID7()[:8]},
				"channel":        "WEB",
			}, nil, []int{200}, "scalper-discover")
		if err != nil {
			continue
		}
		itins := getBookableItineraries(data)
		for _, itin := range itins {
			leg := getFirstLeg(itin)
			if leg == nil {
				continue
			}
			segments = append(segments, &RouteEntry{
				OriginPlace:      places[pr.a],
				DestPlace:        places[pr.b],
				Date:             date,
				ScheduledService: getString(leg, "servicePlanRef"),
				OriginNode:       getString(leg, "originStopRef"),
				DestNode:         getString(leg, "destinationStopRef"),
			})
			if len(segments) >= s.cfg.Scalper.TargetSegments {
				return segments
			}
		}
	}
	return segments
}

func diversifiedSegments(routes []*RouteEntry, target int) []*RouteEntry {
	if len(routes) == 0 {
		return nil
	}
	if len(routes) <= target {
		return routes
	}
	// Simple diversification: spread across routes
	step := len(routes) / target
	if step == 0 {
		step = 1
	}
	var result []*RouteEntry
	for i := 0; i < len(routes) && len(result) < target; i += step {
		result = append(result, routes[i])
	}
	return result
}

func (s *ScalperSim) ensureIdentityVerified(ctx context.Context, travelerID string) (map[string]string, error) {
	tail := fmt.Sprintf("%d", s.rng.Intn(6))
	doc := fmt.Sprintf("loadgen-%s-%s", travelerID, tail)
	documentHash := sha256Hex(doc) + tail
	nameHash := sha256Hex("name-" + travelerID)
	validUntil := time.Now().UTC().Add(365 * 24 * time.Hour).Truncate(time.Second)
	validUntilStr := validUntil.Format(time.RFC3339)

	_, cred, err := s.api.Request(ctx, "POST", "identity-verification",
		"/api/v1/identity-verification/credentials",
		map[string]interface{}{
			"travelerId":             travelerID,
			"profileSnapshotVersion": "loadgen-v1",
			"documentType":           "ID_CARD",
			"maskedDocumentNo":       fmt.Sprintf("LG***********%s", tail),
			"documentHash":           documentHash,
			"canonicalNameHash":      nameHash,
			"validUntil":             validUntilStr,
		}, s.nextHeaders(), []int{200, 201}, "scalper-identity-credential")
	if err != nil {
		return nil, err
	}

	credID := getString(cred, "credentialRecordId")
	material := strings.Join([]string{nameHash, "ID_CARD", documentHash, "",
		validUntilStr, "", "loadgen-v1"}, "|")
	h := sha256.Sum256([]byte(material))
	materialFp := hex.EncodeToString(h[:])

	_, caseData, err := s.api.Request(ctx, "POST", "identity-verification",
		"/api/v1/identity-verification/verification-cases",
		map[string]interface{}{
			"travelerId":          travelerID,
			"credentialRecordId":  credID,
			"purpose":             "ORDER_CREATION",
			"materialFingerprint": materialFp,
			"simPolicyVersion":    "sim-tail-v1",
			"requestedAt":         NowISO(),
		}, s.nextHeaders(), []int{200, 201}, "scalper-identity-verify")
	if err != nil {
		return nil, err
	}

	return map[string]string{
		"identity_credential": credID,
		"identity_case":       getString(caseData, "verificationCaseId"),
	}, nil
}

func (s *ScalperSim) nextAccount() *AccountEntry {
	if len(s.accountPool) == 0 {
		return nil
	}
	entry := s.accountPool[s.accountCursor%len(s.accountPool)]
	s.accountCursor++
	return entry
}

// GrabJourney performs a single scalper grab attempt.
func (s *ScalperSim) GrabJourney(ctx context.Context) (string, error) {
	s.stats.ScalperAttempts.Add(1)

	acct := s.nextAccount()
	if acct == nil {
		return "", &StepError{Step: "scalper-grab", Detail: "no accounts in pool"}
	}
	if len(acct.Travelers) == 0 {
		return "", &StepError{Step: "scalper-grab", Detail: "account has no traveler"}
	}
	tvl := acct.Travelers[0]

	if len(s.targetSegments) == 0 {
		return "", &StepError{Step: "scalper-grab", Detail: "no target segments available"}
	}

	segRoute := s.targetSegments[s.currentTarget%len(s.targetSegments)]
	if s.rng.Float64() < 0.25 {
		s.currentTarget = s.rng.Intn(len(s.targetSegments))
	} else {
		s.currentTarget = (s.currentTarget + 1) % len(s.targetSegments)
	}

	channel := "WEB"
	headers := s.nextHeaders()

	// Search
	_, searchData, err := s.api.Request(ctx, "POST", "trip-planning", "/api/v1/itineraries/search",
		map[string]interface{}{
			"originRef":      segRoute.OriginPlace,
			"destinationRef": segRoute.DestPlace,
			"departureDate":  segRoute.Date,
			"travelerRefs":   []string{tvl},
			"channel":        channel,
		}, headers, []int{200}, "scalper-search")
	if err != nil {
		return "", err
	}
	s.burstPause(ctx)

	itins := getBookableItineraries(searchData)
	if len(itins) == 0 {
		s.stats.ScalperExhausted.Add(1)
		s.currentTarget++
		return "no_itinerary", nil
	}
	itin := itins[s.rng.Intn(len(itins))]
	leg := getFirstLeg(itin)
	segment := getString(leg, "serviceSegmentRef")
	itineraryRef := getString(itin, "itineraryRef")

	// Quote
	_, fareQuote, err := s.api.Request(ctx, "POST", "fare-pricing", "/api/v1/fare-quotes",
		map[string]interface{}{
			"travelerRefs": []string{tvl},
			"channel":      channel,
			"segmentRefs":  []string{segment},
		}, headers, []int{200, 201}, "scalper-quote")
	if err != nil {
		return "", err
	}
	s.burstPause(ctx)

	// Offer (retry on 422)
	var offer map[string]interface{}
	for attempt := 0; attempt < 10; attempt++ {
		_, offer, err = s.api.Request(ctx, "POST", "offer-management", "/api/v1/offers",
			map[string]interface{}{
				"accountId":    acct.AccountID,
				"channelId":    channel,
				"itineraryRef": itineraryRef,
				"travelerRefs": []string{tvl},
			}, headers, []int{200, 201}, "scalper-offer")
		if err == nil {
			break
		}
		if se, ok := err.(*StepError); ok && strings.Contains(se.Detail, "422") && attempt < 9 {
			time.Sleep(time.Duration(float64(time.Second) * 1.0 * pow14(float64(attempt))))
			continue
		}
		return "", err
	}
	s.burstPause(ctx)

	// Order
	_, order, err := s.api.Request(ctx, "POST", "journey-order", "/api/v1/journey-orders",
		map[string]interface{}{
			"accountId":    acct.AccountID,
			"offerId":      getString(offer, "offerId"),
			"offerVersion": getInt(offer, "offerVersion", 1),
			"travelerRefs": []string{tvl},
			"segmentRefs":  []string{segment},
			"journeyDate":  segRoute.Date,
			"productCode":  "TRAIN",
		}, headers, []int{200, 201}, "scalper-order")
	if err != nil {
		return "", err
	}
	orderID := getString(order, "orderId")
	s.burstPause(ctx)

	// Inline reservation via Redis
	saga, sb, noCapacity, err := s.requestInlineReservation(ctx, orderID, segment, tvl)
	if err != nil {
		return "", err
	}
	if noCapacity {
		s.stats.ScalperExhausted.Add(1)
		s.currentTarget++
		return "capacity_exhausted", nil
	}

	// Payment
	totalMinor := getNestedInt(offer, "total", "minorUnits", 10750)
	currency := s.cfg.Currency()
	_, intent, err := s.api.Request(ctx, "POST", "payment", "/api/v1/payment-intents",
		map[string]interface{}{
			"businessRef": orderID,
			"purpose":     "purchase",
			"amount":      map[string]interface{}{"currency": currency, "minorUnits": totalMinor},
			"payerRef":    acct.AccountID,
		}, headers, []int{200, 201}, "scalper-payment-intent")
	if err != nil {
		return "", err
	}
	s.burstPause(ctx)

	// Capture
	intentID := getString(intent, "paymentIntentId")
	_, _, err = s.api.Request(ctx, "POST", "payment",
		"/api/v1/payment-intents/"+url.PathEscape(intentID)+"/capture",
		map[string]interface{}{"channelRef": map[string]interface{}{"channel": "ALIPAY_SIM"}},
		headers, []int{200, 201, 202}, "scalper-payment-capture")
	if err != nil {
		return "", err
	}
	s.burstPause(ctx)

	// Inline ticketing
	_, tickData, err := s.api.Request(ctx, "POST", "entitlement-ticketing", "/api/v1/entitlements",
		map[string]interface{}{
			"segmentBookingId": sb,
			"journeyOrderId":   orderID,
			"travelerRef":      tvl,
			"segmentRef":       segment,
			"issuePurpose":     "INITIAL",
		}, headers, []int{200, 201}, "scalper-ticketing")
	if err != nil {
		return "", err
	}
	ent := getString(tickData, "entitlementId")

	// Confirm
	final := s.pollOrderScalper(ctx, orderID)
	if final != "CONFIRMED" && final != "CONFIRMING" {
		return "", &StepError{Step: "scalper-confirm", Detail: fmt.Sprintf("order %s ended %s", orderID, final)}
	}

	purchase := &Purchase{
		Order:         orderID,
		Saga:          saga,
		SB:            sb,
		Seg:           segment,
		Traveler:      tvl,
		Account:       acct.AccountID,
		Entitlement:   ent,
		TotalMinor:    totalMinor,
		Status:        "confirmed",
		Offer:         getString(offer, "offerId"),
		PaymentIntent: intentID,
		Itinerary:     itineraryRef,
		Quote:         getString(fareQuote, "quoteId"),
	}
	s.reg.AddPurchase(purchase)
	s.stats.ScalperSuccess.Add(1)
	return "grabbed", nil
}

func (s *ScalperSim) requestInlineReservation(ctx context.Context, orderID, segment, traveler string) (string, string, bool, error) {
	saga, err := s.discoverBookingSaga(ctx, orderID)
	if err != nil {
		return "", "", false, err
	}
	sb := "sb-" + UUID7()
	_, _, err = s.api.Request(ctx, "POST", "booking-orchestration",
		"/api/v1/internal/booking-sagas/"+url.PathEscape(saga)+"/request-reservation",
		map[string]interface{}{
			"segmentRef":       segment,
			"travelerRef":      traveler,
			"segmentBookingId": sb,
		}, s.nextHeaders(), []int{200}, "scalper-reservation")
	if err != nil {
		return "", "", false, err
	}
	noCapacity := s.sagaFailedNoCapacity(ctx, saga)
	return saga, sb, noCapacity, nil
}

func (s *ScalperSim) discoverBookingSaga(ctx context.Context, orderID string) (string, error) {
	if s.redis == nil {
		return "", &StepError{Step: "scalper-reservation", Detail: "redis not configured"}
	}
	attempts := s.cfg.Polling.Attempts
	interval := time.Duration(s.cfg.Polling.IntervalSeconds * float64(time.Second))

	for i := 0; i < attempts; i++ {
		entries, err := s.redis.XRevRange(ctx, "events:booking-orchestration", "+", "-").Result()
		if err == nil {
			for _, entry := range entries {
				raw, ok := entry.Values["envelope"].(string)
				if !ok || !strings.Contains(raw, "BookingSagaStarted") || !strings.Contains(raw, orderID) {
					continue
				}
				var env map[string]interface{}
				if json.Unmarshal([]byte(raw), &env) != nil {
					continue
				}
				if getString(env, "eventType") == "BookingSagaStarted" {
					payload, _ := env["payload"].(map[string]interface{})
					if getString(payload, "journeyOrderId") == orderID {
						return getString(payload, "sagaId"), nil
					}
				}
			}
		}
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(interval):
		}
	}
	return "", &StepError{Step: "scalper-reservation", Detail: "no BookingSagaStarted for " + orderID}
}

func (s *ScalperSim) sagaFailedNoCapacity(ctx context.Context, saga string) bool {
	attempts := s.cfg.Polling.Attempts
	interval := time.Duration(s.cfg.Polling.IntervalSeconds * float64(time.Second))

	for i := 0; i < attempts; i++ {
		code, data, _ := s.api.Request(ctx, "GET", "booking-orchestration",
			"/api/v1/internal/booking-sagas/"+url.PathEscape(saga),
			nil, s.nextHeaders(), nil, "scalper-poll-reservation")
		if code == 200 {
			b, _ := json.Marshal(data)
			if strings.Contains(string(b), "NO_AVAILABLE_CAPACITY") {
				return true
			}
			status := getString(data, "status")
			if status == "WAITING_PAYMENT" || status == "HELD" || status == "TICKETING" || status == "COMPLETED" {
				return false
			}
		}
		select {
		case <-ctx.Done():
			return false
		case <-time.After(interval):
		}
	}
	return false
}

func (s *ScalperSim) pollOrderScalper(ctx context.Context, orderID string) string {
	interval := time.Duration(s.cfg.Polling.IntervalSeconds * float64(time.Second))
	deadline := time.After(90 * time.Second)
	for {
		code, data, _ := s.api.Request(ctx, "GET", "journey-order",
			"/api/v1/journey-orders/"+url.PathEscape(orderID),
			nil, s.nextHeaders(), nil, "scalper-poll-order")
		if code == 200 {
			status := getString(data, "status")
			if status == "CONFIRMED" || status == "CONFIRMING" {
				return status
			}
		}
		select {
		case <-ctx.Done():
			return ""
		case <-deadline:
			return ""
		case <-time.After(interval):
		}
	}
}

func (s *ScalperSim) Close() {
	if s.redis != nil {
		s.redis.Close()
	}
}

// ScalperWorker runs one scalper in a tight loop.
func ScalperWorker(ctx context.Context, idx int, cfg *Config, sim *ScalperSim, stats *Stats) {
	if err := sim.Prepare(ctx); err != nil {
		fmt.Printf("[scalper%d] prepare failed - %v\n", idx, err)
		return
	}

	batchSize := cfg.Scalper.PurchaseBatchSize
	retryProb := cfg.Scalper.RetryOnFailure
	if retryProb == 0 {
		retryProb = 0.8
	}

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		for i := 0; i < batchSize; i++ {
			select {
			case <-ctx.Done():
				return
			default:
			}

			outcome, err := sim.GrabJourney(ctx)
			if err != nil {
				stats.RecordJourney("scalper:failed")
				if se, ok := err.(*StepError); ok {
					stats.RecordError("scalper:" + se.Step)
				}
				fmt.Printf("[scalper%d] grab failed - %v\n", idx, err)
				if sim.rng.Float64() >= retryProb {
					break
				}
				continue
			}
			stats.RecordJourney("scalper:" + outcome)
			if outcome == "capacity_exhausted" && sim.currentTarget >= len(sim.targetSegments) {
				fmt.Printf("[scalper%d] all target segments exhausted, idling\n", idx)
				select {
				case <-ctx.Done():
					return
				case <-time.After(30 * time.Second):
					routes := sim.reg.GetRoutes()
					sim.targetSegments = diversifiedSegments(routes, cfg.Scalper.TargetSegments)
					sim.currentTarget = 0
				}
				break
			}
		}

		// Burst pacing
		sim.burstPause(ctx)
	}
}
