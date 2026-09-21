package main

import (
	"context"
	"encoding/json"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"
)

// demandProviders builds a Providers whose registry holds a place for every
// station of the active lines, which is the state Bootstrap leaves behind.
func demandProviders(t *testing.T, cfg *Config) *Providers {
	t.Helper()
	p := newTestProviders(t, newFakeMesh(t, nil), cfg)
	for _, station := range Stations(p.Lines) {
		p.Reg.Places[station.Code] = "plc-" + station.Code
	}
	return p
}

// sampleTrips draws n trips for one persona and returns them.
func sampleTrips(t *testing.T, cfg *Config, persona string, n int) []TripChoice {
	t.Helper()
	p := demandProviders(t, cfg)
	pc, ok := cfg.Personas[persona]
	if !ok {
		t.Fatalf("persona %q is not configured", persona)
	}
	p.ApplyPersona(persona, &pc)

	out := make([]TripChoice, 0, n)
	for i := 0; i < n*4 && len(out) < n; i++ {
		if trip, ok := p.PickTrip(p.Lines); ok {
			out = append(out, trip)
		}
	}
	if len(out) < n {
		t.Fatalf("persona %q yielded only %d trips out of %d attempts: "+
			"its knobs admit almost nothing", persona, len(out), n*4)
	}
	return out
}

// deployedPersonaConfig loads the personas the cluster actually runs. The point
// of the demand model is that THESE are distinguishable, not that some
// hand-written fixture is.
func deployedPersonaConfig(t *testing.T) *Config {
	t.Helper()
	cfg := requireDeployedConfig(t)
	if len(cfg.Personas) < 2 {
		t.Fatalf("the deployed config has %d personas", len(cfg.Personas))
	}
	return cfg
}

// ---------------------------------------------------------------------------
// the personas must be distinguishable in the data
// ---------------------------------------------------------------------------

// TestPersonasAreDistinguishableByWhereAndWhenTheyTravel is the requirement.
// Someone reading a week of records must be able to tell the personas apart by
// where and when they travel, not only by how often they abandon. Before this
// all three drew from the same uniform CityPair(), so these medians were
// identical by construction.
func TestPersonasAreDistinguishableByWhereAndWhenTheyTravel(t *testing.T) {
	cfg := deployedPersonaConfig(t)
	const n = 400

	type profile struct {
		medianKM   int
		medianLead int
		weekendPct float64
	}
	profiles := map[string]profile{}
	for _, persona := range []string{"casual", "business", "power_user"} {
		trips := sampleTrips(t, cfg, persona, n)
		km := make([]int, 0, len(trips))
		lead := make([]int, 0, len(trips))
		weekend := 0
		for _, trip := range trips {
			km = append(km, trip.DistanceKM)
			lead = append(lead, trip.LeadDays)
			departure, err := time.Parse("2006-01-02", trip.Date)
			if err != nil {
				t.Fatalf("%s: departure date %q does not parse: %v", persona, trip.Date, err)
			}
			if isWeekend(departure) {
				weekend++
			}
		}
		p := profile{
			medianKM:   medianInt(km),
			medianLead: medianInt(lead),
			weekendPct: float64(weekend) / float64(len(trips)),
		}
		profiles[persona] = p
		t.Logf("%-11s median %5d km, median lead %2d days, %4.1f%% weekend departures",
			persona, p.medianKM, p.medianLead, p.weekendPct*100)
	}

	business, casual := profiles["business"], profiles["casual"]

	// A business traveller takes SHORT hops; a casual traveller travels far.
	if business.medianKM >= casual.medianKM {
		t.Errorf("business median %d km is not shorter than casual's %d km: "+
			"the personas are indistinguishable by distance",
			business.medianKM, casual.medianKM)
	}
	// And by a wide enough margin that a reader sees it rather than infers it
	// from a statistical test.
	if casual.medianKM < 2*business.medianKM {
		t.Errorf("casual median %d km is less than twice business's %d km: "+
			"the difference is too small to read off the data",
			casual.medianKM, business.medianKM)
	}

	// A business traveller books CLOSE to departure.
	if business.medianLead >= casual.medianLead {
		t.Errorf("business median lead %d days is not shorter than casual's %d: "+
			"'I booked this morning' is not distinguishable from 'I booked a month ago'",
			business.medianLead, casual.medianLead)
	}

	// A business traveller travels on WEEKDAYS; a casual traveller is more
	// likely to travel at a weekend.
	if business.weekendPct >= casual.weekendPct {
		t.Errorf("business departs at a weekend %.1f%% of the time and casual %.1f%%: "+
			"the weekday/weekend distinction is absent",
			business.weekendPct*100, casual.weekendPct*100)
	}
	if business.weekendPct > 0.25 {
		t.Errorf("business departs at a weekend %.1f%% of the time, "+
			"which is not a weekday traveller", business.weekendPct*100)
	}
}

// TestPersonasTravelDifferentLines: a trunk-line preference must actually
// concentrate a persona's demand, or line_weights is inert.
func TestPersonasTravelDifferentLines(t *testing.T) {
	cfg := deployedPersonaConfig(t)
	const n = 400

	shares := map[string]map[string]float64{}
	for _, persona := range []string{"casual", "business"} {
		counts := map[string]int{}
		for _, trip := range sampleTrips(t, cfg, persona, n) {
			counts[trip.Line]++
		}
		shares[persona] = map[string]float64{}
		for line, c := range counts {
			shares[persona][line] = float64(c) / float64(n)
		}
		t.Logf("%-11s line mix: %v", persona, counts)
	}

	// Business concentrates on the two trunk lines.
	trunk := shares["business"]["beijing-shanghai"] + shares["business"]["beijing-guangzhou"]
	if trunk < 0.7 {
		t.Errorf("business takes %.0f%% of its trips on the trunk lines, want the "+
			"majority: line_weights is not concentrating demand", trunk*100)
	}
	// And casual is measurably less concentrated there.
	casualTrunk := shares["casual"]["beijing-shanghai"] + shares["casual"]["beijing-guangzhou"]
	if casualTrunk >= trunk {
		t.Errorf("casual takes %.0f%% of its trips on the trunk lines against "+
			"business's %.0f%%: the personas travel the same network",
			casualTrunk*100, trunk*100)
	}
}

// TestRepeatTripConcentratesDemand: a business traveller takes the same hop
// repeatedly, which is what makes its demand concentrated rather than merely
// short. Measured as distinct station pairs over a fixed number of trips.
func TestRepeatTripConcentratesDemand(t *testing.T) {
	cfg := deployedPersonaConfig(t)
	const n = 300

	distinct := map[string]int{}
	for _, persona := range []string{"casual", "business"} {
		pairs := map[string]bool{}
		for _, trip := range sampleTrips(t, cfg, persona, n) {
			pairs[trip.OriginCode+">"+trip.DestCode] = true
		}
		distinct[persona] = len(pairs)
		t.Logf("%-11s visited %d distinct station pairs in %d trips", persona, len(pairs), n)
	}
	if distinct["business"] >= distinct["casual"] {
		t.Errorf("business visited %d distinct pairs and casual %d: p_repeat_trip "+
			"is not concentrating the business traveller's demand",
			distinct["business"], distinct["casual"])
	}
}

// TestRepeatTripMemoryIsSharedAcrossArrivals: in open-loop mode the scheduler
// builds a fresh Providers per arrival, so a per-Providers trip memory would
// make p_repeat_trip silently never fire in the mode the cluster runs.
func TestRepeatTripMemoryIsSharedAcrossArrivals(t *testing.T) {
	cfg := &Config{
		Behavior: map[string]interface{}{"p_repeat_trip": 1.0},
		Personas: map[string]PersonaConfig{"business": {Weight: 1}},
	}
	first := demandProviders(t, cfg)
	first.ApplyPersona("business", &PersonaConfig{})
	// p_repeat_trip is 1.0, but the memory is empty, so this must draw fresh.
	// Retried: a draw that lands on a band the chosen line has no pair in
	// legitimately yields nothing (the hangzhou-shenzhen line has no trip over
	// 1381 km, so it can offer no "epic" pair at all).
	var seed TripChoice
	var ok bool
	for i := 0; i < 20 && !ok; i++ {
		seed, ok = first.PickTrip(first.Lines)
	}
	if !ok {
		t.Fatal("the first trip could not be drawn")
	}

	// A second Providers over the SAME registry, as an open-loop arrival is.
	second := NewProviders(cfg, first.API, first.Reg, first.Stats, rand.New(rand.NewSource(99)))
	second.ApplyPersona("business", &PersonaConfig{})
	repeat, ok := second.PickTrip(second.Lines)
	if !ok {
		t.Fatal("the repeat trip could not be drawn")
	}
	if repeat.OriginCode != seed.OriginCode || repeat.DestCode != seed.DestCode {
		t.Errorf("with p_repeat_trip=1 a new arrival travelled %s->%s instead of the "+
			"remembered %s->%s: the memory is not shared, so the knob is dead in open-loop",
			repeat.OriginCode, repeat.DestCode, seed.OriginCode, seed.DestCode)
	}
	// The date is redrawn, because a repeated trip is a new booking.
	if repeat.DistanceKM != seed.DistanceKM {
		t.Errorf("repeat distance %d != original %d", repeat.DistanceKM, seed.DistanceKM)
	}
}

// TestRepeatTripIsPerPersona: a business traveller must repeat its OWN hops,
// not whatever a casual traveller happened to book.
func TestRepeatTripIsPerPersona(t *testing.T) {
	reg := NewRegistry()
	rng := rand.New(rand.NewSource(5))
	reg.RememberTrip("business", TripChoice{OriginCode: "BJN", DestCode: "TJS", DistanceKM: 131})
	reg.RememberTrip("casual", TripChoice{OriginCode: "BJN", DestCode: "SHQ", DistanceKM: 1318})

	got, ok := reg.RecentTrip("business", rng)
	if !ok {
		t.Fatal("business has no remembered trip")
	}
	if got.DestCode != "TJS" {
		t.Errorf("business recalled %s->%s, want its own BJN->TJS",
			got.OriginCode, got.DestCode)
	}
	if _, ok := reg.RecentTrip("power_user", rng); ok {
		t.Error("power_user recalled a trip it never took")
	}
}

// TestTripMemoryIsBounded: the memory is a rolling sample of current demand,
// not a log of the whole run.
func TestTripMemoryIsBounded(t *testing.T) {
	reg := NewRegistry()
	for i := 0; i < recentTripsPerPersona*3; i++ {
		reg.RememberTrip("business", TripChoice{OriginCode: "A", DestCode: "B"})
	}
	reg.recentTripsMu.Lock()
	n := len(reg.recentTrips["business"])
	reg.recentTripsMu.Unlock()
	if n != recentTripsPerPersona {
		t.Errorf("the memory holds %d trips, want it capped at %d",
			n, recentTripsPerPersona)
	}
}

// ---------------------------------------------------------------------------
// the knobs themselves
// ---------------------------------------------------------------------------

// TestDistanceBandKnobSelectsTheBand: the knob must actually bound the
// distances drawn, or a persona's travel profile is decorative.
func TestDistanceBandKnobSelectsTheBand(t *testing.T) {
	for band, bounds := range distanceBands {
		cfg := &Config{
			Behavior: map[string]interface{}{
				"trip_distance_km": map[string]interface{}{band: 1.0},
			},
		}
		p := demandProviders(t, cfg)
		p.ApplyPersona("test", &PersonaConfig{})

		drawn := 0
		for i := 0; i < 200; i++ {
			trip, ok := p.PickTrip(p.Lines)
			if !ok {
				continue // that line has no pair in this band, which is a real answer
			}
			drawn++
			if trip.DistanceKM < bounds[0] || trip.DistanceKM >= bounds[1] {
				t.Errorf("band %q admitted a %d km trip, outside [%d, %d)",
					band, trip.DistanceKM, bounds[0], bounds[1])
			}
		}
		if drawn == 0 {
			t.Errorf("band %q drew no trip at all across the whole network", band)
		}
	}
}

// TestUnknownDistanceBandDoesNotSilentlyWiden: a band name no code knows must
// not degrade to "any distance", which would erase the persona distinction the
// knob exists to create.
func TestUnknownDistanceBandDoesNotSilentlyWiden(t *testing.T) {
	cfg := &Config{
		Behavior: map[string]interface{}{
			"trip_distance_km": map[string]interface{}{"commuter": 1.0},
		},
	}
	p := demandProviders(t, cfg)
	p.ApplyPersona("test", &PersonaConfig{})
	if _, ok := p.PickTrip(p.Lines); ok {
		t.Error("an unknown distance band produced a trip: the knob widened silently")
	}
	if p.Stats.JSON() == "" {
		t.Fatal("stats did not render")
	}
	// The error must be counted, so a misconfigured band is visible.
	snap := p.Stats.Snapshot()
	found := false
	for key := range snap.Errors {
		if key == "demand:unknown_distance_band:commuter" {
			found = true
		}
	}
	if !found {
		t.Errorf("no error recorded for the unknown band (errors: %v)", snap.Errors)
	}
}

// TestBookingLeadKnobBoundsTheDate: the lead window must bound the departure
// date, which is what makes a complaint's "I booked X ago" concrete.
func TestBookingLeadKnobBoundsTheDate(t *testing.T) {
	cfg := &Config{
		Behavior: map[string]interface{}{
			"booking_lead_days": map[string]interface{}{"min": 2, "max": 4},
		},
	}
	p := demandProviders(t, cfg)
	p.ApplyPersona("test", &PersonaConfig{})
	today := time.Now().UTC().Truncate(24 * time.Hour)

	for i := 0; i < 200; i++ {
		trip, ok := p.PickTrip(p.Lines)
		if !ok {
			continue
		}
		if trip.LeadDays < 2 || trip.LeadDays > 4 {
			t.Fatalf("lead %d days is outside the configured [2, 4]", trip.LeadDays)
		}
		departure, err := time.Parse("2006-01-02", trip.Date)
		if err != nil {
			t.Fatalf("date %q does not parse: %v", trip.Date, err)
		}
		if want := today.AddDate(0, 0, trip.LeadDays); !departure.Equal(want) {
			t.Fatalf("date %s does not match lead %d days (want %s)",
				trip.Date, trip.LeadDays, want.Format("2006-01-02"))
		}
	}
}

// TestLeadWindowFallsBackToTheInventoryWindow: a config with no per-persona
// lead time must book exactly where it used to, so the created inventory
// covers what customers ask for.
func TestLeadWindowFallsBackToTheInventoryWindow(t *testing.T) {
	cfg := &Config{}
	cfg.Bootstrap.DepartureWindow = DepartureWindow{FromDays: 9, ToDays: 11}
	p := demandProviders(t, cfg)
	p.ApplyPersona("", nil)
	if got := p.leadWindow(); got.Min != 9 || got.Max != 11 {
		t.Errorf("leadWindow = %+v, want the bootstrap departure window {9, 11}", got)
	}

	// A persona override wins over it.
	p.ApplyPersona("business", &PersonaConfig{
		Overrides: map[string]interface{}{
			"booking_lead_days": map[string]interface{}{"min": 1, "max": 3},
		},
	})
	if got := p.leadWindow(); got.Min != 1 || got.Max != 3 {
		t.Errorf("leadWindow = %+v, want the persona's {1, 3}", got)
	}

	// And a bare config falls back to the documented default rather than {0,0},
	// which would book every trip for today.
	bare := demandProviders(t, &Config{})
	bare.ApplyPersona("", nil)
	if got := bare.leadWindow(); got.Min != defaultLeadDays.Min || got.Max != defaultLeadDays.Max {
		t.Errorf("leadWindow on a bare config = %+v, want %+v", got, defaultLeadDays)
	}
}

// TestWeekendKnobShiftsTheDepartureDay: p_weekend_departure must move the
// departure inside the lead window rather than extend it, so the lead-time
// distribution the knob describes still holds.
func TestWeekendKnobShiftsTheDepartureDay(t *testing.T) {
	newCfg := func(pWeekend float64) *Config {
		return &Config{
			Behavior: map[string]interface{}{
				"p_weekend_departure": pWeekend,
				// A window wide enough to contain a day of either kind.
				"booking_lead_days": map[string]interface{}{"min": 1, "max": 14},
			},
		}
	}

	measure := func(pWeekend float64) (weekendShare float64, maxLead int) {
		p := demandProviders(t, newCfg(pWeekend))
		p.ApplyPersona("test", &PersonaConfig{})
		weekend, total := 0, 0
		for i := 0; i < 300; i++ {
			trip, ok := p.PickTrip(p.Lines)
			if !ok {
				continue
			}
			total++
			if trip.LeadDays > maxLead {
				maxLead = trip.LeadDays
			}
			departure, err := time.Parse("2006-01-02", trip.Date)
			if err != nil {
				t.Fatal(err)
			}
			if isWeekend(departure) {
				weekend++
			}
		}
		if total == 0 {
			t.Fatal("no trips drawn")
		}
		return float64(weekend) / float64(total), maxLead
	}

	weekday, weekdayMax := measure(0.0)
	weekendy, weekendMax := measure(1.0)
	if weekday >= weekendy {
		t.Errorf("p_weekend_departure=0 gave %.0f%% weekend departures and p=1 gave %.0f%%",
			weekday*100, weekendy*100)
	}
	if weekendy < 0.9 {
		t.Errorf("p_weekend_departure=1 gave only %.0f%% weekend departures", weekendy*100)
	}
	if weekday > 0.1 {
		t.Errorf("p_weekend_departure=0 still gave %.0f%% weekend departures", weekday*100)
	}
	// The window is not extended in either case.
	if weekdayMax > 14 || weekendMax > 14 {
		t.Errorf("the weekend nudge left the lead window: max lead %d and %d, want <= 14",
			weekdayMax, weekendMax)
	}
}

// TestLineWeightsIgnoreAnInactiveLine: the line set is a deployment choice, so
// a persona preferring a line this run does not create must travel on what
// exists rather than find nothing.
func TestLineWeightsIgnoreAnInactiveLine(t *testing.T) {
	cfg := &Config{
		Behavior: map[string]interface{}{
			"line_weights": map[string]interface{}{"shanghai-kunming": 1.0},
		},
	}
	cfg.Bootstrap.Lines = []string{"beijing-shanghai"}
	p := demandProviders(t, cfg)
	p.ApplyPersona("test", &PersonaConfig{})

	drawn := 0
	for i := 0; i < 100; i++ {
		trip, ok := p.PickTrip(p.Lines)
		if !ok {
			continue
		}
		drawn++
		if trip.Line != "beijing-shanghai" {
			t.Fatalf("trip on %q, but only beijing-shanghai is active", trip.Line)
		}
	}
	if drawn == 0 {
		t.Error("a persona preferring an inactive line drew no trip at all")
	}
}

// ---------------------------------------------------------------------------
// what reaches the services
// ---------------------------------------------------------------------------

// TestTripPairsShareALineAndAreOrdered: every pair a persona travels must be
// two stations on a common line, which the uniform CityPair() could not
// guarantee.
func TestTripPairsShareALineAndAreOrdered(t *testing.T) {
	cfg := deployedPersonaConfig(t)
	byName := map[string]Line{}
	for _, line := range railNetwork {
		byName[line.Name] = line
	}
	for _, persona := range []string{"casual", "business", "power_user"} {
		for _, trip := range sampleTrips(t, cfg, persona, 150) {
			line, ok := byName[trip.Line]
			if !ok {
				t.Fatalf("%s: trip on unknown line %q", persona, trip.Line)
			}
			onLine := map[string]bool{}
			for _, stop := range line.Stops {
				onLine[stop.Station] = true
			}
			if !onLine[trip.OriginCode] || !onLine[trip.DestCode] {
				t.Errorf("%s: %s->%s is not a pair on %s",
					persona, trip.OriginCode, trip.DestCode, trip.Line)
			}
			if trip.OriginCode == trip.DestCode {
				t.Errorf("%s: origin equals destination (%s)", persona, trip.OriginCode)
			}
			if trip.DistanceKM <= 0 {
				t.Errorf("%s: %s->%s has distance %d",
					persona, trip.OriginCode, trip.DestCode, trip.DistanceKM)
			}
		}
	}
}

// TestFareQuoteSendsTheTripDistance: distance is what makes the price follow
// the trip instead of being one constant. fare-pricing accepts distanceKm and
// its default rule set prices at 0.15/km, so a quote without it is flat.
func TestFareQuoteSendsTheTripDistance(t *testing.T) {
	var mu sync.Mutex
	var bodies []map[string]interface{}
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

	cfg := &Config{}
	cfg.Target.BaseURLTemplate = srv.URL + "/%s"
	cfg.Target.RequestTimeoutSeconds = 5
	applyDefaults(cfg)
	stats := NewStats()
	p := NewProviders(cfg, NewApiClient(cfg, stats), NewRegistry(), stats,
		rand.New(rand.NewSource(1)))

	trip := &SearchResult{Segment: "seg-1", Date: "2026-10-15", DistanceKM: 1318, LeadDays: 24}
	if _, err := p.FareQuote(context.Background(), []string{"tvl-1"}, "WEB",
		[]string{"seg-1"}, trip, ""); err != nil {
		t.Fatalf("FareQuote: %v", err)
	}
	if len(bodies) != 1 {
		t.Fatalf("got %d quote bodies, want 1", len(bodies))
	}
	body := bodies[0]
	if got, ok := body["distanceKm"]; !ok {
		t.Error("the quote carried no distanceKm: fare-pricing falls back to a flat fare")
	} else if n, _ := numberFromAny(got); int(n) != 1318 {
		t.Errorf("distanceKm = %v, want 1318", got)
	}
	departure, ok := body["departureTime"].(string)
	if !ok {
		t.Fatal("the quote carried no departureTime: no advance-purchase tier applies")
	}
	if got, err := time.Parse(time.RFC3339, departure); err != nil {
		t.Errorf("departureTime %q is not RFC3339: %v", departure, err)
	} else if got.Format("2006-01-02") != "2026-10-15" {
		t.Errorf("departureTime %q is not on the trip's date", departure)
	}

	// A trip with no known distance must send neither, rather than a made-up
	// distance that would price the trip wrongly.
	bodies = nil
	unknown := &SearchResult{Segment: "seg-2", Date: "2026-10-15"}
	if _, err := p.FareQuote(context.Background(), []string{"tvl-1"}, "WEB",
		[]string{"seg-2"}, unknown, ""); err != nil {
		t.Fatalf("FareQuote: %v", err)
	}
	if len(bodies) != 1 {
		t.Fatalf("got %d quote bodies, want 1", len(bodies))
	}
	if _, ok := bodies[0]["distanceKm"]; ok {
		t.Error("a trip with no known distance still sent distanceKm")
	}
	if _, ok := bodies[0]["departureTime"]; ok {
		t.Error("a trip with no known distance still sent departureTime")
	}
}

// TestSearchCarriesTheTripDistanceThrough: AvailableTrain must hand the
// distance to its caller, or the quote cannot send it.
func TestSearchCarriesTheTripDistanceThrough(t *testing.T) {
	mesh := newFakeMesh(t, func(service, method, path string) (int, map[string]interface{}) {
		if service != "trip-planning" {
			return 0, nil
		}
		// isBookable requires a seg-<uuid> ref, so a short "seg-1" would be
		// filtered out and the search would look empty.
		return 200, map[string]interface{}{
			"itineraries": []interface{}{
				map[string]interface{}{
					"itineraryRef": "iti-1",
					"legs": []interface{}{
						map[string]interface{}{
							"serviceSegmentRef":  "seg-0190f0ab-1111-7000-8000-000000000001",
							"servicePlanRef":     "sp-1",
							"originStopRef":      "node-a",
							"destinationStopRef": "node-b",
						},
					},
				},
			},
		}
	})
	cfg := &Config{
		Behavior: map[string]interface{}{
			"trip_distance_km":  map[string]interface{}{"epic": 1.0},
			"booking_lead_days": map[string]interface{}{"min": 5, "max": 5},
		},
	}
	// One line, and one that actually has an epic-band pair: on a line whose
	// longest trip is shorter than the band, PickTrip correctly yields nothing
	// and personaTrip falls back to a uniform pair with no distance.
	cfg.Bootstrap.Lines = []string{"beijing-guangzhou"}
	p := newTestProviders(t, mesh, cfg)
	for _, station := range Stations(p.Lines) {
		p.Reg.Places[station.Code] = "plc-" + station.Code
	}
	p.ApplyPersona("test", &PersonaConfig{})

	found, err := p.AvailableTrain(context.Background(), []string{"tvl-1"}, "WEB")
	if err != nil {
		t.Fatalf("AvailableTrain: %v", err)
	}
	if found.DistanceKM < 1500 {
		t.Errorf("DistanceKM = %d, want an epic-band distance (>= 1500)", found.DistanceKM)
	}
	if found.LeadDays != 5 {
		t.Errorf("LeadDays = %d, want 5", found.LeadDays)
	}
}

// TestLeadDaysUntilCountsForward guards the lead time recovered for a route
// out of the registry, which has a date but no lead.
func TestLeadDaysUntilCountsForward(t *testing.T) {
	today := time.Now().UTC().Truncate(24 * time.Hour)
	if got := leadDaysUntil(today.AddDate(0, 0, 9).Format("2006-01-02")); got != 9 {
		t.Errorf("leadDaysUntil(+9d) = %d, want 9", got)
	}
	if got := leadDaysUntil(today.AddDate(0, 0, -3).Format("2006-01-02")); got != 0 {
		t.Errorf("leadDaysUntil(past) = %d, want 0", got)
	}
	if got := leadDaysUntil("not-a-date"); got != 0 {
		t.Errorf("leadDaysUntil(garbage) = %d, want 0", got)
	}
}

// TestUniformFallbackIsCountedNotSilent: a run whose registry holds places
// outside the topology reverts to uniform demand, and that must be visible in
// the stats rather than only in the shape of the data.
func TestUniformFallbackIsCountedNotSilent(t *testing.T) {
	cfg := &Config{}
	p := newTestProviders(t, newFakeMesh(t, nil), cfg)
	// Places that are on no line.
	p.Reg.Places["ZZZ1"] = "plc-1"
	p.Reg.Places["ZZZ2"] = "plc-2"
	p.ApplyPersona("test", &PersonaConfig{})

	trip, ok := p.personaTrip()
	if !ok {
		t.Fatal("the fallback produced no trip at all")
	}
	if trip.DistanceKM != 0 {
		t.Errorf("a fallback pair claimed distance %d: it has no line, so there is "+
			"no real distance to claim", trip.DistanceKM)
	}
	snap := p.Stats.Snapshot()
	if snap.Journeys["demand:uniform_fallback"] == 0 {
		t.Errorf("the uniform fallback was not counted (journeys: %v)", snap.Journeys)
	}
}

// TestPersonaDemandSummaryRendersTheKnobs: the startup banner is how an
// operator sees that the personas actually differ.
func TestPersonaDemandSummaryRendersTheKnobs(t *testing.T) {
	cfg := deployedPersonaConfig(t)
	for _, persona := range []string{"casual", "business", "power_user"} {
		summary := PersonaDemandSummary(cfg, persona)
		if summary == "" {
			t.Errorf("%s has no demand summary", persona)
			continue
		}
		for _, knob := range []string{"trip_distance_km", "booking_lead_days",
			"p_weekend_departure", "line_weights", "p_repeat_trip"} {
			if !strings.Contains(summary, knob) {
				t.Errorf("%s summary omits %s: %s", persona, knob, summary)
			}
		}
		t.Logf("%s", summary)
	}
	if got := PersonaDemandSummary(cfg, "nobody"); got != "" {
		t.Errorf("an unconfigured persona rendered %q", got)
	}
}

func medianInt(values []int) int {
	if len(values) == 0 {
		return 0
	}
	sorted := make([]int, len(values))
	copy(sorted, values)
	sort.Ints(sorted)
	return sorted[len(sorted)/2]
}
