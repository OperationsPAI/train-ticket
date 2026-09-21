package main

import (
	"context"
	"encoding/json"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// quoteBodyRecorder captures the fare-quote request bodies the generator sends.
func quoteBodyRecorder(t *testing.T) (*httptest.Server, *[]map[string]interface{}, *sync.Mutex) {
	t.Helper()
	var mu sync.Mutex
	bodies := []map[string]interface{}{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]interface{}
		_ = json.NewDecoder(r.Body).Decode(&body)
		mu.Lock()
		bodies = append(bodies, body)
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(200)
		_ = json.NewEncoder(w).Encode(map[string]interface{}{"quoteId": "q-1"})
	}))
	t.Cleanup(srv.Close)
	return srv, &bodies, &mu
}

func providersAgainst(t *testing.T, url string, cfg *Config) *Providers {
	t.Helper()
	cfg.Target.BaseURLTemplate = url + "/%s"
	cfg.Target.RequestTimeoutSeconds = 5
	applyDefaults(cfg)
	stats := NewStats()
	return NewProviders(cfg, NewApiClient(cfg, stats), NewRegistry(), stats,
		rand.New(rand.NewSource(1)))
}

// TestFareQuoteSendsTheSeatClass is the core of this change: the drawn class
// has to appear on the fare-quote request under the exact field name
// fare-pricing reads (FareQuoteRequest.seatClass), or the knob changes nothing
// about the price.
func TestFareQuoteSendsTheSeatClass(t *testing.T) {
	srv, bodies, mu := quoteBodyRecorder(t)
	p := providersAgainst(t, srv.URL, &Config{})

	trip := &SearchResult{Segment: "seg-1", Date: "2026-10-15", DistanceKM: 120}
	if _, err := p.FareQuote(context.Background(), []string{"tvl-1"}, "WEB",
		[]string{"seg-1"}, trip, "BUSINESS_CLASS", ""); err != nil {
		t.Fatalf("FareQuote: %v", err)
	}

	mu.Lock()
	defer mu.Unlock()
	if len(*bodies) != 1 {
		t.Fatalf("got %d quote bodies, want 1", len(*bodies))
	}
	got, ok := (*bodies)[0]["seatClass"]
	if !ok {
		t.Fatal("the quote carried no seatClass: every fare would price at the " +
			"SECOND_CLASS default regardless of the configured mix")
	}
	if got != "BUSINESS_CLASS" {
		t.Errorf("seatClass = %v, want BUSINESS_CLASS", got)
	}
}

// TestSeatClassDrawIsInFarePricingVocabulary guards the rename. Every key the
// generator can draw must be one fare-pricing accepts: _seat_class_multiplier
// raises PricingError on anything else, so an abbreviation like the old
// "SECOND" would fail the quote outright rather than pricing at 1.0.
func TestSeatClassDrawIsInFarePricingVocabulary(t *testing.T) {
	// The exact value set of SeatClassMultiplier in
	// services/fare-pricing/src/fare_pricing/domain.py.
	want := map[string]bool{
		"SECOND_CLASS": true, "FIRST_CLASS": true, "BUSINESS_CLASS": true,
		"STANDING": true, "SLEEPER_HARD": true, "SLEEPER_SOFT": true,
	}
	if len(fareSeatClasses) != len(want) {
		t.Errorf("fareSeatClasses has %d entries, want %d", len(fareSeatClasses), len(want))
	}
	for class := range want {
		if !fareSeatClasses[class] {
			t.Errorf("fareSeatClasses is missing %q, which fare-pricing prices", class)
		}
	}
	for class := range fareSeatClasses {
		if !want[class] {
			t.Errorf("fareSeatClasses has %q, which fare-pricing rejects with PricingError", class)
		}
	}

	// And the default mix must only name classes in that set.
	for class := range defaultSeatClassMix {
		if !fareSeatClasses[class] {
			t.Errorf("defaultSeatClassMix draws %q, which fare-pricing rejects", class)
		}
	}
}

// TestSeatAssignmentClassesAreStocked pins the narrower set. seat-assignment's
// seat map is built from DefaultCRH380AConfig, whose coaches are only
// business/first/second, and filterAvailable matches on exact equality, so a
// request for any other class matches zero seats.
func TestSeatAssignmentClassesAreStocked(t *testing.T) {
	want := map[string]bool{"SECOND_CLASS": true, "FIRST_CLASS": true, "BUSINESS_CLASS": true}
	for class := range want {
		if !seatAssignmentClasses[class] {
			t.Errorf("seatAssignmentClasses is missing %q", class)
		}
	}
	for class := range seatAssignmentClasses {
		if !want[class] {
			t.Errorf("seatAssignmentClasses has %q, which the seat map stocks no seats for", class)
		}
		// Anything sent to seat-assignment must also be priceable, or the
		// journey would seat a class it could not quote.
		if !fareSeatClasses[class] {
			t.Errorf("seatAssignmentClasses has %q, which fare-pricing does not price", class)
		}
	}
}

// TestSeatClassHonoursPersonaOverride: the knob lives under behavior:, so a
// persona's overrides must replace it. This is what makes the class a
// per-persona property rather than one global constant.
func TestSeatClassHonoursPersonaOverride(t *testing.T) {
	cfg := &Config{
		Behavior: map[string]interface{}{
			"seat_classes": map[string]interface{}{"SECOND_CLASS": 1.0},
		},
	}
	p := providersAgainst(t, "http://127.0.0.1:1", cfg)

	if class := p.SeatClassOrDefault(); class != "SECOND_CLASS" {
		t.Fatalf("global mix drew %q, want SECOND_CLASS", class)
	}

	p.ApplyPersona("business", &PersonaConfig{
		Overrides: map[string]interface{}{
			"seat_classes": map[string]interface{}{"FIRST_CLASS": 1.0},
		},
	})
	if class := p.SeatClassOrDefault(); class != "FIRST_CLASS" {
		t.Errorf("persona override ignored: drew %q, want FIRST_CLASS", class)
	}
}

// TestSeatClassRejectsAnUnknownClass: a class outside the vocabulary must be
// counted and refused rather than quietly becoming second class, because
// fare-pricing would reject the quote and the run would lose that demand to a
// failure whose cause is in the ConfigMap.
func TestSeatClassRejectsAnUnknownClass(t *testing.T) {
	cfg := &Config{
		Behavior: map[string]interface{}{
			// The old abbreviation, which fare-pricing does not accept.
			"seat_classes": map[string]interface{}{"SECOND": 1.0},
		},
	}
	p := providersAgainst(t, "http://127.0.0.1:1", cfg)

	if class, ok := p.SeatClass(); ok {
		t.Errorf("drew %q from a vocabulary fare-pricing rejects, want refusal", class)
	}
	if got := p.Stats.Snapshot().Errors["seat_class:unknown:SECOND"]; got == 0 {
		t.Error("an unknown seat class was not counted, so the misconfiguration would be silent")
	}
}

// TestAssignSeatSendsTheClassForStockedClasses: the class has to reach
// seat-assignment under the field name its AssignSeatsRequest reads.
func TestAssignSeatSendsTheClassForStockedClasses(t *testing.T) {
	var mu sync.Mutex
	var bodies []map[string]interface{}
	m := newFakeMesh(t, nil)
	m.srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]interface{}
		_ = json.NewDecoder(r.Body).Decode(&body)
		mu.Lock()
		bodies = append(bodies, body)
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(201)
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"assignments": []interface{}{
				map[string]interface{}{"assignmentId": "asg-1", "seatId": "06-01A"},
			},
		})
	})
	p := newTestProviders(t, m, &Config{})

	got := p.AssignSeat(context.Background(), "seg-1", "2026-10-15",
		[]string{"tvl-1"}, "FIRST_CLASS")
	if got != "asg-1" {
		t.Errorf("AssignSeat returned %q, want asg-1", got)
	}

	mu.Lock()
	defer mu.Unlock()
	if len(bodies) != 1 {
		t.Fatalf("got %d seat-assignment bodies, want 1", len(bodies))
	}
	if bodies[0]["seatClass"] != "FIRST_CLASS" {
		t.Errorf("seatClass = %v, want FIRST_CLASS", bodies[0]["seatClass"])
	}
}

// TestAssignSeatSkipsClassesWithNoSeats is the other half, and the half that
// makes the restriction real: STANDING and the sleeper classes must not be sent
// to seat-assignment at all. Measured against the assignment engine, each of
// the three matches zero seats and yields "no matching seat available", so
// sending them would 422 on every call rather than when a train is full.
func TestAssignSeatSkipsClassesWithNoSeats(t *testing.T) {
	for _, class := range []string{"STANDING", "SLEEPER_HARD", "SLEEPER_SOFT"} {
		m := newFakeMesh(t, nil)
		p := newTestProviders(t, m, &Config{})

		if got := p.AssignSeat(context.Background(), "seg-1", "2026-10-15",
			[]string{"tvl-1"}, class); got != "" {
			t.Errorf("%s: AssignSeat returned %q, want empty", class, got)
		}
		if n := m.servicesHit()["seat-assignment"]; n != 0 {
			t.Errorf("%s: seat-assignment got %d requests, want 0", class, n)
		}
		// The outcome is recorded, so a run whose personas draw mostly
		// seatless classes is visible rather than looking like no seat traffic
		// was ever attempted.
		key := "seat_assignment:no_seat_for_class:" + class
		if p.Stats.Snapshot().Journeys[key] == 0 {
			t.Errorf("%s: outcome %q was not recorded", class, key)
		}
	}
}

// TestAssignSeatTreatsSoldOutAsAnOutcome: a 422 from seat-assignment means
// every seat of the class is held. That is a real user outcome, so it must not
// land in the error tally.
func TestAssignSeatTreatsSoldOutAsAnOutcome(t *testing.T) {
	m := newFakeMesh(t, func(service, method, path string) (int, map[string]interface{}) {
		if service == "seat-assignment" {
			return 422, map[string]interface{}{"code": "NO_SEAT_AVAILABLE"}
		}
		return 0, nil
	})
	p := newTestProviders(t, m, &Config{})

	if got := p.AssignSeat(context.Background(), "seg-1", "2026-10-15",
		[]string{"tvl-1"}, "BUSINESS_CLASS"); got != "" {
		t.Errorf("AssignSeat returned %q on a sold-out class, want empty", got)
	}
	if p.Stats.Snapshot().Journeys["seat_assignment:class_sold_out:BUSINESS_CLASS"] == 0 {
		t.Error("a sold-out class was not recorded as a journey outcome")
	}
	for key, n := range p.Stats.Snapshot().Errors {
		if strings.HasPrefix(key, "seat-assignment") && n > 0 {
			t.Errorf("a sold-out class was counted as error %q (%d): "+
				"422 is the expected answer for a full train", key, n)
		}
	}
}

// TestDeployedPersonasDifferInSeatClass closes the loop on the point of the
// change: the three personas must not all draw the same class mix, and every
// class they name must be one fare-pricing prices.
func TestDeployedPersonasDifferInSeatClass(t *testing.T) {
	cfg := requireDeployedConfig(t)

	mixes := map[string]map[string]float64{}
	for name, persona := range cfg.Personas {
		raw, ok := persona.Overrides["seat_classes"]
		if !ok {
			t.Errorf("persona %q has no seat_classes override: it cannot differ "+
				"from the global mix", name)
			continue
		}
		mix := map[string]float64{}
		asMap, ok := raw.(map[string]interface{})
		if !ok {
			t.Errorf("persona %q seat_classes did not decode as a map", name)
			continue
		}
		var total float64
		for class, weight := range asMap {
			if !fareSeatClasses[class] {
				t.Errorf("persona %q draws %q, which fare-pricing rejects with PricingError",
					name, class)
			}
			n, _ := numberFromAny(weight)
			mix[class] = n
			total += n
		}
		if total <= 0 {
			t.Errorf("persona %q seat_classes weights sum to 0: nothing would be drawn", name)
		}
		mixes[name] = mix
	}

	if len(mixes) < 2 {
		t.Skip("fewer than two personas carry a seat_classes override")
	}
	// A business traveller in first class and a casual one in second are
	// different rows in the record, and that difference is the whole point.
	for _, pair := range [][2]string{{"casual", "business"}, {"business", "power_user"}} {
		a, aOK := mixes[pair[0]]
		b, bOK := mixes[pair[1]]
		if !aOK || !bOK {
			continue
		}
		if sameMix(a, b) {
			t.Errorf("personas %q and %q draw an identical seat class mix: "+
				"the knob adds no heterogeneity", pair[0], pair[1])
		}
	}

	// The business persona is the premium one, so its seated-premium share must
	// exceed the casual persona's. This is the assertion that would catch the
	// two mixes being swapped, which sameMix alone would not.
	casual, cOK := mixes["casual"]
	business, bOK := mixes["business"]
	if cOK && bOK {
		if premiumShare(business) <= premiumShare(casual) {
			t.Errorf("business premium share %.2f <= casual %.2f: the business "+
				"traveller is meant to buy the expensive classes",
				premiumShare(business), premiumShare(casual))
		}
	}
}

func sameMix(a, b map[string]float64) bool {
	if len(a) != len(b) {
		return false
	}
	for k, v := range a {
		if b[k] != v {
			return false
		}
	}
	return true
}

// premiumShare is the weight a mix puts on the two classes that cost more than
// second, normalized by its total.
func premiumShare(mix map[string]float64) float64 {
	var total, premium float64
	for class, weight := range mix {
		total += weight
		if class == "FIRST_CLASS" || class == "BUSINESS_CLASS" {
			premium += weight
		}
	}
	if total <= 0 {
		return 0
	}
	return premium / total
}
