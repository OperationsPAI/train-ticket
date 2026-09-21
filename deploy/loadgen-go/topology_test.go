package main

import (
	"math/rand"
	"strings"
	"testing"
	"time"
)

// ---------------------------------------------------------------------------
// the topology itself
// ---------------------------------------------------------------------------

// TestRailNetworkIsWellFormed enforces the invariants the rest of the code
// relies on. The topology is a hand-written table, and a stop out of order or
// at a duplicated kilometre would produce a zero-distance trip that prices as
// the minimum fare, or a negative one -- both silently, at the first search of
// a deployed run.
func TestRailNetworkIsWellFormed(t *testing.T) {
	if len(railNetwork) < 2 {
		t.Fatalf("the network has %d lines, want several", len(railNetwork))
	}
	seenLine := map[string]bool{}
	for _, line := range railNetwork {
		if line.Name == "" {
			t.Error("a line has no name")
		}
		if seenLine[line.Name] {
			t.Errorf("duplicate line name %q", line.Name)
		}
		seenLine[line.Name] = true
		if line.AvgSpeedKMH <= 0 {
			t.Errorf("%s: avg speed %d, want > 0 (it divides a distance)",
				line.Name, line.AvgSpeedKMH)
		}
		if len(line.Stops) < 3 {
			t.Errorf("%s has %d stops, want enough for intermediate stops to exist",
				line.Name, len(line.Stops))
		}
		if line.Stops[0].KM != 0 {
			t.Errorf("%s starts at km %d, want 0", line.Name, line.Stops[0].KM)
		}
		seenStop := map[string]bool{}
		for i, stop := range line.Stops {
			if _, ok := stations[stop.Station]; !ok {
				t.Errorf("%s stop %d references unknown station %q",
					line.Name, i, stop.Station)
			}
			if seenStop[stop.Station] {
				t.Errorf("%s visits %s twice", line.Name, stop.Station)
			}
			seenStop[stop.Station] = true
			// Strictly increasing: equal positions would make a real station
			// pair a zero-distance trip.
			if i > 0 && stop.KM <= line.Stops[i-1].KM {
				t.Errorf("%s: %s at km %d is not beyond %s at km %d",
					line.Name, stop.Station, stop.KM,
					line.Stops[i-1].Station, line.Stops[i-1].KM)
			}
		}
	}
	// Every station in the table must be on a line, or it is inventory nothing
	// ever creates.
	onALine := map[string]bool{}
	for _, line := range railNetwork {
		for _, stop := range line.Stops {
			onALine[stop.Station] = true
		}
	}
	for code, station := range stations {
		if !onALine[code] {
			t.Errorf("station %s (%s) is on no line", code, station.Name)
		}
		if station.Code != code {
			t.Errorf("station keyed %q carries code %q", code, station.Code)
		}
		if station.Name == "" {
			t.Errorf("station %s has no name", code)
		}
	}
}

// TestNetworkIsConnectedBySharedStations: the lines must intersect, or the
// topology is four unrelated corridors and no persona's line preference can
// concentrate demand on shared traffic.
func TestNetworkIsConnectedBySharedStations(t *testing.T) {
	linesPerStation := map[string][]string{}
	for _, line := range railNetwork {
		for _, stop := range line.Stops {
			linesPerStation[stop.Station] = append(linesPerStation[stop.Station], line.Name)
		}
	}
	shared := 0
	for station, lines := range linesPerStation {
		if len(lines) < 2 {
			continue
		}
		shared++
		// A shared station is measured from each line's own origin, so its two
		// kilometre positions legitimately differ. What must hold is that it
		// really is on both.
		t.Logf("%s (%s) is on %s", station, StationName(station), strings.Join(lines, " + "))
	}
	if shared < 2 {
		t.Errorf("%d stations are shared between lines, want several: "+
			"without them the network is disjoint corridors", shared)
	}
}

// TestTheCorpusOfPlausibleJourneysIsLarge is the point of the change. The
// previous four-city config admitted twelve directed pairs, all equally
// likely.
func TestTheCorpusOfPlausibleJourneysIsLarge(t *testing.T) {
	total := 0
	for _, line := range railNetwork {
		total += len(LinePairs(line))
	}
	if total < 500 {
		t.Errorf("the network admits %d directed station pairs, want many hundreds "+
			"(the four-city config admitted 12)", total)
	}
	t.Logf("%d plausible directed journeys across %d lines and %d stations",
		total, len(railNetwork), len(stations))
}

// ---------------------------------------------------------------------------
// distance and duration are derivable
// ---------------------------------------------------------------------------

// TestTripDistanceIsTheRealDistanceAlongTheLine: distance must be an actual
// kilometre figure, not an ordinal, because fare-pricing multiplies it by a
// per-km rate.
func TestTripDistanceIsTheRealDistanceAlongTheLine(t *testing.T) {
	var bjs Line
	for _, line := range railNetwork {
		if line.Name == "beijing-shanghai" {
			bjs = line
		}
	}
	if bjs.Name == "" {
		t.Fatal("the beijing-shanghai line is missing")
	}

	cases := []struct {
		origin, dest string
		wantKM       int
		wantMid      int
	}{
		// The full run, and the short hop at its head.
		{"BJN", "SHQ", 1318, 14},
		{"BJN", "TJS", 131, 1},
		// A mid-line pair, and the same pair reversed: same distance, same
		// intermediate count. Jinan West to Nanjing South passes Qufu,
		// Tengzhou, Xuzhou, Suzhou and Bengbu.
		{"JNX", "NJN", 604, 5},
		{"NJN", "JNX", 604, 5},
		// Adjacent stops have no intermediate stop at all.
		{"WXD", "SZB", 26, 0},
	}
	for _, c := range cases {
		var found *TripPair
		for _, pair := range LinePairs(bjs) {
			if pair.OriginCode == c.origin && pair.DestCode == c.dest {
				p := pair
				found = &p
				break
			}
		}
		if found == nil {
			t.Errorf("%s->%s is not a pair on the line", c.origin, c.dest)
			continue
		}
		if found.DistanceKM != c.wantKM {
			t.Errorf("%s->%s distance = %d km, want %d",
				c.origin, c.dest, found.DistanceKM, c.wantKM)
		}
		if found.Intermediate != c.wantMid {
			t.Errorf("%s->%s intermediate stops = %d, want %d",
				c.origin, c.dest, found.Intermediate, c.wantMid)
		}
	}
}

// TestDurationFollowsDistanceAndStops: a 131 km hop and a 1318 km run must not
// be scheduled to take the same time, which is what the previous random
// arrival hour produced.
func TestDurationFollowsDistanceAndStops(t *testing.T) {
	short := TripPair{DistanceKM: 131, Intermediate: 1}
	long := TripPair{DistanceKM: 1318, Intermediate: 14}

	shortMin := short.DurationMinutes(300)
	longMin := long.DurationMinutes(300)
	if shortMin >= longMin {
		t.Errorf("a 131 km trip takes %d min and a 1318 km trip %d min: "+
			"duration does not follow distance", shortMin, longMin)
	}
	// 131 km at 300 km/h is ~26 min running, plus one 2-min stop.
	if shortMin != 131*60/300+dwellMinutesPerStop {
		t.Errorf("short trip duration = %d min, want running time plus dwell", shortMin)
	}
	// Two trips of equal distance differ by how many stops they pass.
	few := TripPair{DistanceKM: 600, Intermediate: 2}.DurationMinutes(300)
	many := TripPair{DistanceKM: 600, Intermediate: 9}.DurationMinutes(300)
	if many <= few {
		t.Errorf("equal-distance trips with 2 and 9 intermediate stops take %d and %d min: "+
			"intermediate stops do not lengthen the journey", few, many)
	}
	// A line's speed must matter too, or every line is the same line.
	fast := TripPair{DistanceKM: 1000}.DurationMinutes(300)
	slow := TripPair{DistanceKM: 1000}.DurationMinutes(235)
	if slow <= fast {
		t.Errorf("1000 km takes %d min at 300 km/h and %d min at 235 km/h", fast, slow)
	}
}

// TestEveryRealDurationIsPositiveAndPlausible guards the arrival times
// Bootstrap sends: service-plan requires arrivalTime strictly after
// departureTime for a segment, so a zero-length duration is a 400.
func TestEveryRealDurationIsPositiveAndPlausible(t *testing.T) {
	for _, line := range railNetwork {
		for _, pair := range LinePairs(line) {
			mins := pair.DurationMinutes(line.AvgSpeedKMH)
			if mins <= 0 {
				t.Errorf("%s %s->%s: duration %d min, want > 0 "+
					"(service-plan rejects arrival <= departure)",
					line.Name, pair.OriginCode, pair.DestCode, mins)
			}
			// Nothing on this network is a multi-day journey, and a departure
			// plus duration must stay on a sane clock.
			if mins > 20*60 {
				t.Errorf("%s %s->%s: duration %d min is implausible",
					line.Name, pair.OriginCode, pair.DestCode, mins)
			}
		}
	}
}

// ---------------------------------------------------------------------------
// line selection
// ---------------------------------------------------------------------------

// TestActiveLinesRejectsAnUnknownName: a typo in bootstrap.lines must not
// silently remove a share of the demand.
func TestActiveLinesRejectsAnUnknownName(t *testing.T) {
	if _, err := ActiveLines([]string{"beijing-shanghai", "beijing-shangai"}); err == nil {
		t.Error("a misspelled line name was accepted: that run would lose its demand silently")
	}
	lines, err := ActiveLines([]string{"hangzhou-shenzhen", "beijing-shanghai"})
	if err != nil {
		t.Fatalf("ActiveLines: %v", err)
	}
	if len(lines) != 2 {
		t.Fatalf("got %d lines, want 2", len(lines))
	}
	// Table order, not the order the names were given, so the resulting
	// inventory is stable across restarts.
	if lines[0].Name != "beijing-shanghai" {
		t.Errorf("lines[0] = %q, want the table's order", lines[0].Name)
	}
	all, err := ActiveLines(nil)
	if err != nil {
		t.Fatalf("ActiveLines(nil): %v", err)
	}
	if len(all) != len(railNetwork) {
		t.Errorf("no selection gave %d lines, want the whole network (%d)",
			len(all), len(railNetwork))
	}
}

// TestStationsAreDistinctAcrossLines: a station shared by two lines must yield
// one place, not two. place-network puts no uniqueness constraint on `code`,
// so a duplicate here would create a second place with the same code and the
// registry would point at whichever came last.
func TestStationsAreDistinctAcrossLines(t *testing.T) {
	lines, err := ActiveLines(nil)
	if err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	for _, station := range Stations(lines) {
		if seen[station.Code] {
			t.Errorf("station %s returned twice", station.Code)
		}
		seen[station.Code] = true
	}
	if len(seen) != len(stations) {
		t.Errorf("Stations returned %d stations, want all %d", len(seen), len(stations))
	}
	// Shanghai Hongqiao is on two lines and must appear once.
	count := 0
	for _, station := range Stations(lines) {
		if station.Code == "SHQ" {
			count++
		}
	}
	if count != 1 {
		t.Errorf("Shanghai Hongqiao appears %d times, want 1", count)
	}
}

// ---------------------------------------------------------------------------
// inventory covers what the personas ask for
// ---------------------------------------------------------------------------

// TestInventorySpreadsAcrossTheDistanceSpectrum: a uniform draw over station
// pairs is dominated by long trips, so a business persona asking for a short
// hop would find nothing to book. The inventory must cover every band.
func TestInventorySpreadsAcrossTheDistanceSpectrum(t *testing.T) {
	lines, err := ActiveLines(nil)
	if err != nil {
		t.Fatal(err)
	}
	rng := rand.New(rand.NewSource(7))

	// One deployed day's worth.
	bands := map[string]int{}
	for date := 0; date < 7; date++ {
		for _, pair := range InventoryPairs(lines, 24, rng) {
			bands[DistanceBandOf(pair.DistanceKM)]++
		}
	}
	for _, band := range []string{"short", "medium", "long", "epic"} {
		if bands[band] == 0 {
			t.Errorf("a week of inventory contains no %s trip: a persona preferring "+
				"that band finds nothing to book (got %v)", band, bands)
		}
	}
	t.Logf("a week of inventory by distance band: %v", bands)
}

// TestInventoryIsDividedAcrossLines: adding a line must widen the network at a
// fixed inventory cost, not multiply the created services.
func TestInventoryIsDividedAcrossLines(t *testing.T) {
	rng := rand.New(rand.NewSource(11))
	one, err := ActiveLines([]string{"beijing-shanghai"})
	if err != nil {
		t.Fatal(err)
	}
	all, err := ActiveLines(nil)
	if err != nil {
		t.Fatal(err)
	}
	oneCount := len(InventoryPairs(one, 24, rng))
	allCount := len(InventoryPairs(all, 24, rng))
	if allCount > oneCount+len(all) {
		t.Errorf("24 per date gave %d pairs on one line and %d on four: "+
			"the budget is multiplied by the line count, not divided",
			oneCount, allCount)
	}
	// Every active line must actually receive inventory.
	byLine := map[string]int{}
	for _, pair := range InventoryPairs(all, 24, rng) {
		byLine[pair.Line]++
	}
	for _, line := range all {
		if byLine[line.Name] == 0 {
			t.Errorf("line %s received no inventory", line.Name)
		}
	}
}

// TestInventoryPairsAreRealTrips: every created service must be a pair on a
// common line with a real distance, which is what the previous uniform draw
// over city codes could not guarantee.
func TestInventoryPairsAreRealTrips(t *testing.T) {
	lines, err := ActiveLines(nil)
	if err != nil {
		t.Fatal(err)
	}
	byName := map[string]Line{}
	for _, line := range lines {
		byName[line.Name] = line
	}
	rng := rand.New(rand.NewSource(3))
	for _, pair := range InventoryPairs(lines, 40, rng) {
		line, ok := byName[pair.Line]
		if !ok {
			t.Fatalf("pair claims line %q, which is not active", pair.Line)
		}
		var oKM, dKM = -1, -1
		for _, stop := range line.Stops {
			if stop.Station == pair.OriginCode {
				oKM = stop.KM
			}
			if stop.Station == pair.DestCode {
				dKM = stop.KM
			}
		}
		if oKM < 0 || dKM < 0 {
			t.Errorf("%s->%s is not a pair on %s",
				pair.OriginCode, pair.DestCode, pair.Line)
			continue
		}
		want := oKM - dKM
		if want < 0 {
			want = -want
		}
		if pair.DistanceKM != want {
			t.Errorf("%s %s->%s: distance %d, want %d",
				pair.Line, pair.OriginCode, pair.DestCode, pair.DistanceKM, want)
		}
		if pair.DistanceKM == 0 {
			t.Errorf("%s %s->%s has zero distance", pair.Line, pair.OriginCode, pair.DestCode)
		}
	}
}

// TestDistanceBandsTileTheRange: the bands must have no gap, or a real trip
// length would fall into no band and the persona knob could never select it.
func TestDistanceBandsTileTheRange(t *testing.T) {
	for km := 0; km < 3000; km += 7 {
		if DistanceBandOf(km) == "" {
			t.Fatalf("%d km falls into no distance band", km)
		}
	}
	// And every real trip on the network lands in one.
	for _, line := range railNetwork {
		for _, pair := range LinePairs(line) {
			if DistanceBandOf(pair.DistanceKM) == "" {
				t.Errorf("%s %s->%s at %d km falls into no band",
					line.Name, pair.OriginCode, pair.DestCode, pair.DistanceKM)
			}
		}
	}
}

// TestMustDatePanicsOnAMalformedDate: a bad departure_dates entry must stop
// the run rather than seed a 1970 departure that every search then misses.
func TestMustDatePanicsOnAMalformedDate(t *testing.T) {
	defer func() {
		if recover() == nil {
			t.Error("mustDate accepted a malformed date")
		}
	}()
	mustDate("2026-13-45")
}

func TestMustDateParsesADate(t *testing.T) {
	got := mustDate("2026-09-21")
	want := time.Date(2026, 9, 21, 0, 0, 0, 0, time.UTC)
	if !got.Equal(want) {
		t.Errorf("mustDate = %v, want %v", got, want)
	}
}
