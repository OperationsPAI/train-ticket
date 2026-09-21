package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math/rand"
	"net/url"
	"strings"
	"time"
)

// Providers is the composable building-block layer. Each method is an atomic
// provider; journeys compose them.
type Providers struct {
	Cfg   *Config
	API   *ApiClient
	Reg   *Registry
	Stats *Stats
	Rng   *rand.Rand

	// Active behavior context (global merged with persona)
	Ctx        map[string]interface{}
	JourneyMix map[string]float64

	// Persona is the name of the persona currently applied, which the demand
	// model needs in order to find that persona's recent trips.
	Persona string

	// Lines is the active route topology, resolved once at startup from
	// bootstrap.lines. The demand model picks trips out of it.
	Lines []Line
}

func NewProviders(cfg *Config, api *ApiClient, reg *Registry, stats *Stats, rng *rand.Rand) *Providers {
	ctx := make(map[string]interface{})
	for k, v := range cfg.Behavior {
		ctx[k] = v
	}
	jm := make(map[string]float64)
	for k, v := range cfg.Journey {
		jm[k] = v
	}
	// The active topology is derived from config here rather than passed in, so
	// every Providers in the process agrees on it without threading a
	// parameter through six call sites. An unknown line name is a startup
	// error, already reported by ResolveLines in main; here it degrades to the
	// whole network so a unit test that builds a Providers from a bare Config
	// still gets a topology.
	lines, err := ActiveLines(cfg.Bootstrap.Lines)
	if err != nil {
		lines = railNetwork
	}
	return &Providers{
		Cfg:        cfg,
		API:        api,
		Reg:        reg,
		Stats:      stats,
		Rng:        rng,
		Ctx:        ctx,
		JourneyMix: jm,
		Lines:      lines,
	}
}

// ApplyPersona merges a persona's overrides on top of global behavior.
//
// name is the persona's configured key, or "" when no persona is applied. It
// is kept because the demand model needs to find this persona's own recent
// trips, and PersonaConfig carries no name of its own.
func (p *Providers) ApplyPersona(name string, persona *PersonaConfig) {
	p.Persona = name
	p.Ctx = make(map[string]interface{})
	for k, v := range p.Cfg.Behavior {
		p.Ctx[k] = v
	}
	p.JourneyMix = make(map[string]float64)
	for k, v := range p.Cfg.Journey {
		p.JourneyMix[k] = v
	}
	if persona != nil {
		for k, v := range persona.Overrides {
			p.Ctx[k] = v
		}
		if persona.JourneyWeights != nil {
			p.JourneyMix = make(map[string]float64)
			for k, v := range persona.JourneyWeights {
				p.JourneyMix[k] = v
			}
		}
	}
}

// PickPersona selects a weighted persona, returning its configured name
// alongside it. The name is returned because it is the only identifier a
// persona has -- PersonaConfig carries weights and overrides, not its own key
// -- and the journey record has to say which kind of customer an attempt was.
// Both results are zero when no personas are configured.
func (p *Providers) PickPersona() (string, *PersonaConfig) {
	if len(p.Cfg.Personas) == 0 {
		return "", nil
	}
	weights := make(map[string]float64)
	for name, pc := range p.Cfg.Personas {
		w := pc.Weight
		if w <= 0 {
			w = 1
		}
		weights[name] = w
	}
	chosen := WeightedChoice(p.Rng, weights)
	pc := p.Cfg.Personas[chosen]
	return chosen, &pc
}

// CtxFloat reads a float from the active behavior context.
func (p *Providers) CtxFloat(key string, fallback float64) float64 {
	v, ok := p.Ctx[key]
	if !ok {
		return fallback
	}
	switch t := v.(type) {
	case float64:
		return t
	case int:
		return float64(t)
	default:
		return fallback
	}
}

// CtxMap reads a map[string]float64 from the context.
func (p *Providers) CtxMap(key string, fallback map[string]float64) map[string]float64 {
	v, ok := p.Ctx[key]
	if !ok {
		return fallback
	}
	switch m := v.(type) {
	case map[string]interface{}:
		out := make(map[string]float64, len(m))
		for k, val := range m {
			switch n := val.(type) {
			case float64:
				out[k] = n
			case int:
				out[k] = float64(n)
			}
		}
		return out
	case map[string]float64:
		return m
	default:
		return fallback
	}
}

// Chance returns true with probability from the context key.
func (p *Providers) Chance(key string) bool {
	return p.Rng.Float64() < p.CtxFloat(key, 0)
}

// OptionalChance reads a probability key, falling back to default.
func (p *Providers) OptionalChance(key string, def float64) bool {
	return p.Rng.Float64() < p.CtxFloat(key, def)
}

// LongTailChance returns true with the probability configured under long_tail,
// and always false when the whole long_tail section is disabled.
func (p *Providers) LongTailChance(key string, def float64) bool {
	if !p.Cfg.LongTailEnabled() {
		return false
	}
	return p.Rng.Float64() < p.Cfg.LongTailFloat(key, def)
}

// Currency returns the configured currency code.
func (p *Providers) Currency() string {
	return p.Cfg.Currency()
}

// DefaultAmount returns a default monetary amount.
func (p *Providers) DefaultAmount(key string, fallback int) int {
	return p.Cfg.DefaultInt(key, fallback)
}

// --- Atomic providers ---

// Account provides or creates an account.
func (p *Providers) Account(ctx context.Context) (*AccountEntry, error) {
	pNew := p.CtxFloat("p_new_account", 0.35)
	if p.Rng.Float64() >= pNew {
		entry := p.Reg.PickAccount(p.Rng)
		if entry != nil {
			code, _, _ := p.API.Request(ctx, "GET", "account",
				"/api/v1/accounts/"+url.PathEscape(entry.AccountID),
				nil, nil, nil, "login")
			if code == 200 {
				return entry, nil
			}
		}
	}
	accountID := "acc-" + UUID7()
	_, _, err := p.API.Request(ctx, "POST", "account", "/api/v1/accounts",
		map[string]interface{}{"accountId": accountID}, nil, []int{200, 201}, "register-account")
	if err != nil {
		return nil, err
	}
	return p.Reg.AddAccount(accountID), nil
}

// Traveler provides or creates a traveler for the given account.
func (p *Providers) Traveler(ctx context.Context, entry *AccountEntry, exclude []string) (string, error) {
	pNew := p.CtxFloat("p_new_traveler", 0.50)
	pool := filterStrings(entry.Travelers, exclude)
	if len(pool) > 0 && p.Rng.Float64() >= pNew {
		tvl := pool[p.Rng.Intn(len(pool))]
		code, _, _ := p.API.Request(ctx, "GET", "traveler-profile",
			"/api/v1/travelers/"+url.PathEscape(tvl), nil, nil, nil, "get-traveler")
		if code == 200 {
			return tvl, nil
		}
	}
	given, family := RandName(p.Rng)
	travelerTypes := p.CtxMap("traveler_types", map[string]float64{
		"ADULT": 0.85, "CHILD": 0.10, "SENIOR": 0.05,
	})
	_, data, err := p.API.Request(ctx, "POST", "traveler-profile", "/api/v1/travelers",
		map[string]interface{}{
			"accountId":    entry.AccountID,
			"travelerType": WeightedChoice(p.Rng, travelerTypes),
			"givenName":    given,
			"familyName":   family,
		}, nil, []int{200, 201}, "create-traveler")
	if err != nil {
		return "", err
	}
	tvl := getString(data, "travelerId")
	p.Reg.mu.Lock()
	entry.Travelers = append(entry.Travelers, tvl)
	if len(entry.Travelers) > 20 {
		entry.Travelers = entry.Travelers[1:]
	}
	p.Reg.mu.Unlock()
	return tvl, nil
}

// Identity ensures a traveler has a verified identity credential.
func (p *Providers) Identity(ctx context.Context, travelerID string) (map[string]string, error) {
	tail := fmt.Sprintf("%d", p.Rng.Intn(6))
	doc := fmt.Sprintf("loadgen-%s-%s", travelerID, tail)
	documentHash := sha256Hex(doc) + tail
	nameHash := sha256Hex("name-" + travelerID)
	validUntil := time.Now().UTC().Add(365 * 24 * time.Hour).Truncate(time.Second)
	validUntilStr := validUntil.Format(time.RFC3339)

	credBody := map[string]interface{}{
		"travelerId":              travelerID,
		"profileSnapshotVersion":  "loadgen-v1",
		"documentType":            "ID_CARD",
		"maskedDocumentNo":        fmt.Sprintf("LG***********%s", tail),
		"documentHash":            documentHash,
		"canonicalNameHash":       nameHash,
		"validUntil":              validUntilStr,
	}
	_, cred, err := p.API.Request(ctx, "POST", "identity-verification",
		"/api/v1/identity-verification/credentials",
		credBody, nil, []int{200, 201}, "identity-credential")
	if err != nil {
		return nil, err
	}

	credID := getString(cred, "credentialRecordId")
	material := strings.Join([]string{nameHash, "ID_CARD", documentHash, "",
		validUntilStr, "", "loadgen-v1"}, "|")
	materialFp := sha256Hex(material)

	verifyBody := map[string]interface{}{
		"travelerId":          travelerID,
		"credentialRecordId":  credID,
		"purpose":             "ORDER_CREATION",
		"materialFingerprint": materialFp,
		"simPolicyVersion":    "sim-tail-v1",
		"requestedAt":         NowISO(),
	}
	_, caseData, err := p.API.Request(ctx, "POST", "identity-verification",
		"/api/v1/identity-verification/verification-cases",
		verifyBody, nil, []int{200, 201}, "identity-verify")
	if err != nil {
		return nil, err
	}

	return map[string]string{
		"identity_credential": credID,
		"identity_case":       getString(caseData, "verificationCaseId"),
	}, nil
}

// CityPair picks an origin/destination pair the active persona would travel.
//
// Both stations are on a common line, in that line's own order, at a distance
// the persona's trip_distance_km band allows. It is no longer a uniform draw
// over every registered place: that made every pair equally likely, so no
// origin/destination was more plausible than another and all three personas
// travelled identically.
//
// See personaTrip for the demand model and for when it falls back to a uniform
// pair.
func (p *Providers) CityPair() (originCode, destCode, originPlace, destPlace string, err error) {
	trip, ok := p.personaTrip()
	if !ok {
		return "", "", "", "", &StepError{Step: "city-pair",
			Detail: "fewer than 2 places in registry", Kind: FailureUnavailable}
	}
	return trip.OriginCode, trip.DestCode, trip.originPlace, trip.destPlace, nil
}

// placeFor resolves a station code to the placeId Bootstrap created for it.
func (p *Providers) placeFor(code string) (string, bool) {
	p.Reg.mu.Lock()
	defer p.Reg.mu.Unlock()
	id, ok := p.Reg.Places[code]
	return id, ok && id != ""
}

// DepartureDate picks a date in the booking window.
func (p *Providers) DepartureDate() string {
	bs := p.Cfg.Bootstrap
	if len(bs.DepartureDates) > 0 {
		return bs.DepartureDates[p.Rng.Intn(len(bs.DepartureDates))]
	}
	fromDays := bs.DepartureWindow.FromDays
	if fromDays == 0 {
		fromDays = 7
	}
	toDays := bs.DepartureWindow.ToDays
	if toDays == 0 {
		toDays = 21
	}
	today := time.Now().UTC().Truncate(24 * time.Hour)
	offset := p.Rng.Intn(toDays-fromDays+1) + fromDays
	return today.Add(time.Duration(offset) * 24 * time.Hour).Format("2006-01-02")
}

// SearchResult holds the output of a trip search.
type SearchResult struct {
	Itinerary   string
	Segment     string
	Service     string
	OriginNode  string
	DestNode    string
	Date        string
	OriginPlace string
	DestPlace   string

	// DistanceKM is the real distance of the trip along its line, and
	// LeadDays how far ahead of departure it was searched for. Both are sent
	// on the fare quote, which is what makes the price follow the trip:
	// fare-pricing's default rule set prices at 0.15/km with a 500 km discount
	// threshold, and applies an advance-purchase multiplier from
	// departureTime. 0 means the trip did not come from the topology, in which
	// case neither is sent and the quote falls back to the flat base fare.
	DistanceKM int
	LeadDays   int
}

// AvailableTrain searches for bookable itineraries.
func (p *Providers) AvailableTrain(ctx context.Context, travelers []string, channel string) (*SearchResult, error) {
	p.Reg.mu.Lock()
	numPlaces := len(p.Reg.Places)
	p.Reg.mu.Unlock()

	if numPlaces >= 2 {
		if trip, ok := p.personaTrip(); ok {
			_, data, err := p.API.Request(ctx, "POST", "trip-planning", "/api/v1/itineraries/search",
				map[string]interface{}{
					"originRef":      trip.originPlace,
					"destinationRef": trip.destPlace,
					"departureDate":  trip.Date,
					"travelerRefs":   travelers,
					"channel":        channel,
				}, nil, []int{200}, "search")
			if err == nil {
				itins := getBookableItineraries(data)
				if len(itins) > 0 {
					itin := itins[p.Rng.Intn(len(itins))]
					result := extractSearchResult(itin, trip.Date, trip.originPlace, trip.destPlace)
					result.DistanceKM = trip.DistanceKM
					result.LeadDays = trip.LeadDays
					return result, nil
				}
			}
		}
	}

	// Fallback to known routes
	routes := p.Reg.GetRoutes()
	if len(routes) == 0 {
		// The failure behind the resident deployment's 100% purchase and browse
		// failure rate. From the customer's side the search page showed
		// nothing to book.
		return nil, &StepError{Step: "available-train",
			Detail: "no routes and fewer than 2 places", Kind: FailureUnavailable}
	}
	route := routes[p.Rng.Intn(len(routes))]
	_, data, err := p.API.Request(ctx, "POST", "trip-planning", "/api/v1/itineraries/search",
		map[string]interface{}{
			"originRef":      route.OriginPlace,
			"destinationRef": route.DestPlace,
			"departureDate":  route.Date,
			"travelerRefs":   travelers,
			"channel":        channel,
		}, nil, []int{200}, "search")
	if err != nil {
		return nil, err
	}
	itins := getBookableItineraries(data)
	if len(itins) == 0 {
		return nil, &StepError{Step: "search",
			Detail: fmt.Sprintf("no bookable itinerary for %s", route.Date),
			Kind:   FailureUnavailable}
	}
	itin := itins[p.Rng.Intn(len(itins))]
	result := extractSearchResult(itin, route.Date, route.OriginPlace, route.DestPlace)
	result.DistanceKM = route.DistanceKM
	result.LeadDays = leadDaysUntil(route.Date)
	if result.Service == "" {
		result.Service = route.ScheduledService
	}
	if result.OriginNode == "" {
		result.OriginNode = route.OriginNode
	}
	if result.DestNode == "" {
		result.DestNode = route.DestNode
	}
	return result, nil
}

// FareQuote gets a fare quote for a searched trip.
//
// The trip's distance and departure date are sent, which is what makes the
// quoted price follow the trip instead of being one constant for every
// journey: fare-pricing's default rule set prices the base fare at 0.15/km
// with a 10% discount on distance beyond 500 km, and applies an
// advance-purchase multiplier derived from `departureTime`
// (_base_fare_for_distance and _advance_purchase_tier in
// services/fare-pricing/src/fare_pricing/domain.py).
//
// Both fields are omitted when the trip carries no distance, e.g. a route the
// bootstrap sweep discovered rather than created from a line. fare-pricing
// falls back to the flat base fare for a null distanceKm, which is the honest
// answer when the length of the trip is genuinely unknown; sending a made-up
// distance would price it wrongly instead.
//
// seatClass is the persona's drawn class and multiplies the base fare, so the
// same trip quotes differently for a business traveller in first class and a
// casual one in second. The caller passes the class it will also send to
// seat-assignment, so one journey prices and seats the same class.
func (p *Providers) FareQuote(ctx context.Context, travelers []string, channel string,
	segments []string, trip *SearchResult, seatClass string) (map[string]interface{}, error) {
	body := map[string]interface{}{
		"travelerRefs": travelers,
		"channel":      channel,
		"segmentRefs":  segments,
	}
	if seatClass != "" {
		body["seatClass"] = seatClass
	}
	if trip != nil && trip.DistanceKM > 0 {
		body["distanceKm"] = trip.DistanceKM
		if departure, err := time.Parse("2006-01-02", trip.Date); err == nil {
			// A date, not a timestamp: the only time information the topology
			// fixes is the day. Midday UTC keeps the advance-purchase tier on
			// the intended day in either direction of the timezone.
			body["departureTime"] = departure.Add(12 * time.Hour).Format(time.RFC3339)
		}
	}
	_, q, err := p.API.Request(ctx, "POST", "fare-pricing", "/api/v1/fare-quotes",
		body, nil, []int{200, 201}, "quote")
	return q, err
}

// Offer creates an offer with retry on 422.
//
// The 422 is expected, not a fault: offer-management rejects an itinerary whose
// FareQuoted it has not consumed yet, so the first attempts race the event bus
// and the retry loop below exists precisely to absorb that. PollRequest keeps
// those attempts out of the error tally -- counted as errors they were the
// second-largest entry in the stats (213 against 378 successes) on a run where
// every purchase journey completed.
func (p *Providers) Offer(ctx context.Context, accountID, channel, itinerary string, travelers []string) (map[string]interface{}, error) {
	for attempt := 0; attempt < 8; attempt++ {
		// The LAST attempt is not an expected-status poll: if it still 422s the
		// offer genuinely could not be created and that must show up as an error.
		var expected []int
		if attempt < 7 {
			expected = []int{422}
		}
		_, o, err := p.API.PollRequest(ctx, "POST", "offer-management", "/api/v1/offers",
			map[string]interface{}{
				"accountId":    accountID,
				"channelId":    channel,
				"itineraryRef": itinerary,
				"travelerRefs": travelers,
			}, nil, []int{200, 201}, "offer", expected)
		if err == nil {
			return o, nil
		}
		if se, ok := err.(*StepError); ok && strings.Contains(se.Detail, "422") && attempt < 7 {
			backoff := 0.5 * pow14(float64(attempt))
			if backoff > 5.0 {
				backoff = 5.0
			}
			time.Sleep(time.Duration(backoff * float64(time.Second)))
			continue
		}
		return nil, err
	}
	// Eight attempts, every one refused. The person was shown an error on the
	// page that turns a search into a bookable offer.
	return nil, &StepError{Step: "offer", Detail: "exhausted retries",
		Kind: FailureRejected}
}

// Order creates a journey order.
func (p *Providers) Order(ctx context.Context, accountID string, offer map[string]interface{}, travelers, segments []string, date string) (map[string]interface{}, error) {
	_, o, err := p.API.Request(ctx, "POST", "journey-order", "/api/v1/journey-orders",
		map[string]interface{}{
			"accountId":    accountID,
			"offerId":      getString(offer, "offerId"),
			"offerVersion": getInt(offer, "offerVersion", 1),
			"travelerRefs": travelers,
			"segmentRefs":  segments,
			"journeyDate":  date,
			"productCode":  "TRAIN",
		}, nil, []int{200, 201}, "order")
	return o, err
}

// PaymentIntent creates a payment intent.
func (p *Providers) PaymentIntent(ctx context.Context, orderID string, amountMinor int, payer string) (map[string]interface{}, error) {
	_, intent, err := p.API.Request(ctx, "POST", "payment", "/api/v1/payment-intents",
		map[string]interface{}{
			"businessRef": orderID,
			"purpose":     "purchase",
			"amount": map[string]interface{}{
				"currency":   p.Currency(),
				"minorUnits": amountMinor,
			},
			"payerRef": payer,
		}, nil, []int{200, 201}, "payment-intent")
	return intent, err
}

// PaymentCapture captures a payment intent.
func (p *Providers) PaymentCapture(ctx context.Context, intentID string, faultSeed string) error {
	channelRef := map[string]interface{}{"channel": "ALIPAY_SIM"}
	if faultSeed != "" {
		channelRef["faultSeedRef"] = faultSeed
	}
	_, _, err := p.API.Request(ctx, "POST", "payment",
		"/api/v1/payment-intents/"+url.PathEscape(intentID)+"/capture",
		map[string]interface{}{"channelRef": channelRef}, nil, []int{200, 201, 202}, "payment-capture")
	return err
}

// --- Helpers ---

func getBookableItineraries(data map[string]interface{}) []map[string]interface{} {
	itinsRaw, _ := data["itineraries"].([]interface{})
	var result []map[string]interface{}
	for _, raw := range itinsRaw {
		itin, ok := raw.(map[string]interface{})
		if !ok {
			continue
		}
		if isBookable(itin) {
			result = append(result, itin)
		}
	}
	return result
}

func isBookable(itin map[string]interface{}) bool {
	legsRaw, _ := itin["legs"].([]interface{})
	if len(legsRaw) == 0 {
		return false
	}
	leg, _ := legsRaw[0].(map[string]interface{})
	if leg == nil {
		return false
	}
	ref := getString(leg, "serviceSegmentRef")
	if !strings.HasPrefix(ref, "seg-") {
		return false
	}
	uuidPart := ref[4:]
	return len(uuidPart) == 36 && strings.Count(uuidPart, "-") == 4
}

func extractSearchResult(itin map[string]interface{}, date, originPlace, destPlace string) *SearchResult {
	legsRaw, _ := itin["legs"].([]interface{})
	leg, _ := legsRaw[0].(map[string]interface{})
	return &SearchResult{
		Itinerary:   getString(itin, "itineraryRef"),
		Segment:     getString(leg, "serviceSegmentRef"),
		Service:     getString(leg, "servicePlanRef"),
		OriginNode:  getString(leg, "originStopRef"),
		DestNode:    getString(leg, "destinationStopRef"),
		Date:        date,
		OriginPlace: originPlace,
		DestPlace:   destPlace,
	}
}

func sha256Hex(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

func pow14(exp float64) float64 {
	result := 1.0
	base := 1.4
	for i := 0; i < int(exp); i++ {
		result *= base
	}
	return result
}

func filterStrings(slice []string, exclude []string) []string {
	exSet := make(map[string]struct{}, len(exclude))
	for _, e := range exclude {
		exSet[e] = struct{}{}
	}
	var out []string
	for _, s := range slice {
		if _, found := exSet[s]; !found {
			out = append(out, s)
		}
	}
	return out
}
