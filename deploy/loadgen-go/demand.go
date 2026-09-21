package main

import (
	"sort"
	"strconv"
	"strings"
	"time"
)

// Per-persona demand: what a persona travels, and when it books.
//
// Before this, all three personas drew from the same uniform CityPair() over
// four city codes, so they differed only in how often they abandoned. Demand
// is now part of the persona, and the difference is visible in the data: a week
// of records can be split by persona on trip distance, on how far ahead the
// booking was made, and on whether the departure falls on a weekday.
//
// NO SCHEMA CHANGE. PersonaConfig.Overrides is a map[string]interface{} that
// journeys already read through Providers.CtxFloat and Providers.CtxMap, and
// every knob below is read the same way. A persona expresses its demand with
// ordinary override keys, so a deployment can retune a persona's geography
// without a code change, exactly as it can retune its abandon rate.

// The demand knobs, all read through the behavior context: a value in
// `behavior:` is the default for every persona, and a persona's `overrides:`
// replaces it. Written as string literals at the call sites below, never as
// named constants, because deployed_config_test.go's dead-key guard scrapes
// the literal argument of CtxFloat/CtxMap to decide whether a configured knob
// is actually read -- a constant would make every one of these look inert.
//
//   trip_distance_km    weight map over the named distance bands. Separates a
//                       business traveller's short intercity hop from a casual
//                       traveller's long trip.
//   booking_lead_days   {min, max} days ahead of departure this persona books.
//                       A business traveller booking three weeks ahead is not
//                       realistic, and this is what makes "I booked this
//                       morning for a train this afternoon" distinguishable
//                       from "I booked a month ago".
//   p_weekend_departure probability the departure falls on a weekend. A
//                       business traveller travels on weekdays.
//   line_weights        weight map over line names. A trunk-line preference
//                       concentrates business demand on Beijing-Shanghai and
//                       Beijing-Guangzhou.
//   p_repeat_trip       probability of travelling a station pair this persona
//                       has already travelled, rather than drawing a new one.
//                       What makes business demand concentrated rather than
//                       merely short.

// distanceBands are the named bands trip_distance_km weights over. Named
// rather than numeric so a persona's configuration reads as a statement about
// what kind of travel it does.
//
// Bounds are in kilometres and are inclusive of min, exclusive of max, with
// the last band open-ended. They are chosen against the topology: on the
// Beijing-Shanghai line `short` reaches Dezhou East, `medium` reaches Xuzhou
// East, and `long` is the full Beijing to Shanghai run.
var distanceBands = map[string][2]int{
	"short":  {0, 350},
	"medium": {350, 800},
	"long":   {800, 1500},
	"epic":   {1500, 1 << 30},
}

// defaultDistanceMix is the band mix for a run whose config sets no
// trip_distance_km anywhere, including one with personas removed. Spread across
// the spectrum rather than concentrated, so a bare config still exercises the
// whole fare range.
var defaultDistanceMix = map[string]float64{
	"short": 0.30, "medium": 0.30, "long": 0.25, "epic": 0.15,
}

// defaultLeadDays is the booking window for a run that configures none. It is
// the departure_window this replaces, so a config without the knob books
// exactly where it used to.
var defaultLeadDays = RangeSeconds{Min: 7, Max: 21}

// TripChoice is one persona's answer to "where am I going and when".
type TripChoice struct {
	Line       string
	OriginCode string
	DestCode   string
	DistanceKM int
	Date       string // departure date, YYYY-MM-DD
	LeadDays   int    // days between today and the departure date
}

// PickTrip chooses a trip for the persona currently applied to p.
//
// Returns false when the topology offers nothing the persona's knobs allow,
// which a caller must treat as "no trip available" rather than substituting a
// random pair: silently widening a persona's demand to whatever exists is what
// would make the personas indistinguishable again.
func (p *Providers) PickTrip(lines []Line) (TripChoice, bool) {
	if len(lines) == 0 {
		return TripChoice{}, false
	}

	// A repeat traveller goes where it went before. Checked first, because the
	// point of the knob is that the pair does NOT get redrawn.
	//
	// The recent-trip memory lives on the Registry, not on this Providers: in
	// open-loop mode the scheduler builds a fresh Providers for every arrival
	// (scheduler.go's RunOpenLoop), so a per-Providers memory would be empty
	// on every journey and the knob would silently never fire in the mode the
	// cluster actually runs.
	if p.Chance("p_repeat_trip") {
		if repeat, ok := p.Reg.RecentTrip(p.Persona, p.Rng); ok {
			repeat.Date, repeat.LeadDays = p.pickDepartureDate()
			return repeat, true
		}
	}

	band := WeightedChoice(p.Rng, p.CtxMap("trip_distance_km", defaultDistanceMix))
	bounds, known := distanceBands[band]
	if !known {
		// A band name no code knows is a configuration error that must not
		// silently become "any distance": it would erase the persona
		// distinction this whole knob exists to create.
		p.Stats.RecordError("demand:unknown_distance_band:" + band)
		return TripChoice{}, false
	}

	// The DISTANCE is the persona's statement about what kind of travel it
	// does, and the line is a preference about where. So when the preferred
	// line cannot offer the band -- hangzhou-shenzhen has no trip over 1381 km,
	// so it can offer no "epic" pair at all -- redraw the line rather than give
	// up: giving up would send that share of the persona's demand to the
	// uniform fallback, which would dilute exactly the distance distinction
	// this knob exists to create.
	//
	// Bounded by the number of active lines, so a band no line can offer ends
	// as "no trip" instead of looping.
	var candidates []TripPair
	tried := make(map[string]bool, len(lines))
	for attempt := 0; attempt < len(lines); attempt++ {
		candidate, ok := p.pickLine(lines)
		if !ok {
			return TripChoice{}, false
		}
		if tried[candidate.Name] {
			// Already rejected, or a weighted draw repeating itself. Sweep the
			// remaining lines in order rather than redrawing indefinitely.
			found := false
			for _, l := range lines {
				if !tried[l.Name] {
					candidate, found = l, true
					break
				}
			}
			if !found {
				break
			}
		}
		tried[candidate.Name] = true
		if inBand := pairsInBand(candidate, bounds); len(inBand) > 0 {
			candidates = inBand
			break
		}
		p.Stats.RecordJourney("demand:no_pair_in_band:" + candidate.Name + ":" + band)
	}
	if len(candidates) == 0 {
		// No active line has a pair in this band at all.
		p.Stats.RecordJourney("demand:band_unavailable:" + band)
		return TripChoice{}, false
	}
	pair := candidates[p.Rng.Intn(len(candidates))]

	date, lead := p.pickDepartureDate()
	choice := TripChoice{
		Line:       pair.Line,
		OriginCode: pair.OriginCode,
		DestCode:   pair.DestCode,
		DistanceKM: pair.DistanceKM,
		Date:       date,
		LeadDays:   lead,
	}
	p.Reg.RememberTrip(p.Persona, choice)
	return choice, true
}

// pickLine chooses a line by the persona's line_weights, falling back to a
// uniform draw over the active lines when the persona expresses no preference.
//
// A weight naming an inactive line is ignored rather than failing: the line set
// is a deployment choice (`bootstrap.lines`) and a persona that prefers a line
// this run does not create should travel on what exists.
func (p *Providers) pickLine(lines []Line) (Line, bool) {
	weights := p.CtxMap("line_weights", nil)
	if len(weights) > 0 {
		active := make(map[string]float64, len(lines))
		for _, line := range lines {
			if w, ok := weights[line.Name]; ok && w > 0 {
				active[line.Name] = w
			}
		}
		if len(active) > 0 {
			chosen := WeightedChoice(p.Rng, active)
			for _, line := range lines {
				if line.Name == chosen {
					return line, true
				}
			}
		}
	}
	return lines[p.Rng.Intn(len(lines))], true
}

// pickDepartureDate draws a booking lead time from the persona's
// booking_lead_days and returns the departure date it implies.
//
// The persona's p_weekend_departure then nudges the date onto or off a weekend,
// within the same lead window: a business traveller's Friday booking for
// Monday and a casual traveller's booking for Saturday are the same lead time
// and different trips. Nudging inside the window rather than extending it keeps
// the lead-time distribution the knob describes.
func (p *Providers) pickDepartureDate() (string, int) {
	lead := p.leadWindow()
	minDays := int(lead.Min)
	maxDays := int(lead.Max)
	if maxDays < minDays {
		maxDays = minDays
	}

	days := minDays
	if maxDays > minDays {
		days = minDays + p.Rng.Intn(maxDays-minDays+1)
	}

	today := time.Now().UTC().Truncate(24 * time.Hour)
	wantWeekend := p.Rng.Float64() < p.CtxFloat("p_weekend_departure", 2.0/7.0)
	if isWeekend(today.AddDate(0, 0, days)) != wantWeekend {
		// Walk forward inside the window for a day of the wanted kind. At most
		// seven steps are needed to find one, and the window is at least a day
		// wide, so this either finds one or leaves the draw alone.
		for probe := minDays; probe <= maxDays; probe++ {
			if isWeekend(today.AddDate(0, 0, probe)) == wantWeekend {
				days = probe
				break
			}
		}
	}
	return today.AddDate(0, 0, days).Format("2006-01-02"), days
}

// leadWindow resolves booking_lead_days for the active persona.
//
// Read through CtxMap, which already turns a YAML {min, max} into
// map[string]float64, so the knob is consumed the same way every other weight
// map in this package is.
//
// bootstrap.departure_window remains the fallback, so a config that sets no
// per-persona lead time books exactly where it did before this change, and the
// created inventory is guaranteed to cover the window customers ask for.
func (p *Providers) leadWindow() RangeSeconds {
	if window := p.CtxMap("booking_lead_days", nil); len(window) > 0 {
		out := RangeSeconds{Min: window["min"], Max: window["max"]}
		if out.Max < out.Min {
			out.Max = out.Min
		}
		return out
	}
	bs := p.Cfg.Bootstrap.DepartureWindow
	if bs.FromDays > 0 || bs.ToDays > 0 {
		return RangeSeconds{Min: float64(bs.FromDays), Max: float64(bs.ToDays)}
	}
	return defaultLeadDays
}

func numberFromAny(raw interface{}) (float64, bool) {
	switch v := raw.(type) {
	case float64:
		return v, true
	case int:
		return float64(v), true
	default:
		return 0, false
	}
}

// leadDaysUntil is how many days ahead of a YYYY-MM-DD departure date today
// is. Negative or unparseable yields 0, which means "do not claim a lead time"
// to the fare quote.
func leadDaysUntil(date string) int {
	departure, err := time.Parse("2006-01-02", date)
	if err != nil {
		return 0
	}
	days := int(departure.Sub(time.Now().UTC().Truncate(24*time.Hour)).Hours() / 24)
	if days < 0 {
		return 0
	}
	return days
}

func isWeekend(t time.Time) bool {
	switch t.Weekday() {
	case time.Saturday, time.Sunday:
		return true
	default:
		return false
	}
}

// pairsInBand returns the line's station pairs whose distance falls in the
// band. Both travel directions are kept, so a persona's repeated hop can be a
// return journey.
func pairsInBand(line Line, bounds [2]int) []TripPair {
	var out []TripPair
	for _, pair := range LinePairs(line) {
		if pair.DistanceKM >= bounds[0] && pair.DistanceKM < bounds[1] {
			out = append(out, pair)
		}
	}
	return out
}

// resolvedTrip is a TripChoice whose station codes have been resolved to the
// placeIds trip-planning is searched with.
type resolvedTrip struct {
	TripChoice
	originPlace string
	destPlace   string
}

// personaTrip picks a trip for the active persona and resolves its stations to
// the placeIds trip-planning is searched with.
//
// This is the single place that turns a persona's demand into a trip, so
// AvailableTrain and CityPair cannot disagree about what the persona travels.
//
// The uniform fallback exists for a run whose registry holds places outside the
// topology, or whose lines were narrowed after the places were seeded. Such a
// journey still offers load, and the fallback is counted so a run that
// silently reverted to uniform demand shows up in the stats rather than only in
// the shape of the data. A fallback pair carries no distance: it has no line,
// so there is no real distance to claim, and sending a wrong one would price
// the trip wrongly.
func (p *Providers) personaTrip() (resolvedTrip, bool) {
	if trip, ok := p.PickTrip(p.Lines); ok {
		origin, oOK := p.placeFor(trip.OriginCode)
		dest, dOK := p.placeFor(trip.DestCode)
		if oOK && dOK {
			return resolvedTrip{TripChoice: trip, originPlace: origin, destPlace: dest}, true
		}
		// The topology named stations this run never created.
		p.Stats.RecordError("demand:station_not_in_registry")
	}

	p.Reg.mu.Lock()
	codes := make([]string, 0, len(p.Reg.Places))
	places := make(map[string]string, len(p.Reg.Places))
	for k, v := range p.Reg.Places {
		codes = append(codes, k)
		places[k] = v
	}
	p.Reg.mu.Unlock()
	if len(codes) < 2 {
		return resolvedTrip{}, false
	}
	p.Stats.RecordJourney("demand:uniform_fallback")
	sort.Strings(codes) // map order is random; the draw below supplies the randomness
	i := p.Rng.Intn(len(codes))
	j := p.Rng.Intn(len(codes) - 1)
	if j >= i {
		j++
	}
	date, lead := p.pickDepartureDate()
	out := resolvedTrip{originPlace: places[codes[i]], destPlace: places[codes[j]]}
	out.OriginCode = codes[i]
	out.DestCode = codes[j]
	out.Date = date
	out.LeadDays = lead
	return out, true
}

// DistanceBandOf names the band a distance falls in, so a record or a log can
// say which band a trip was without the reader re-deriving the bounds.
func DistanceBandOf(km int) string {
	for _, name := range []string{"short", "medium", "long", "epic"} {
		b := distanceBands[name]
		if km >= b[0] && km < b[1] {
			return name
		}
	}
	return ""
}

// PersonaDemandSummary renders a persona's demand knobs as one line, for the
// startup banner. An operator has to be able to see that the personas actually
// differ in where they travel, not only in the config file.
func PersonaDemandSummary(cfg *Config, name string) string {
	persona, ok := cfg.Personas[name]
	if !ok {
		return ""
	}
	get := func(key string) interface{} {
		if persona.Overrides != nil {
			if v, ok := persona.Overrides[key]; ok {
				return v
			}
		}
		if cfg.Behavior != nil {
			return cfg.Behavior[key]
		}
		return nil
	}
	var parts []string
	for _, key := range []string{"trip_distance_km", "booking_lead_days",
		"p_weekend_departure", "line_weights", "p_repeat_trip"} {
		if v := get(key); v != nil {
			parts = append(parts, key+"="+compactValue(v))
		}
	}
	if len(parts) == 0 {
		return name + ": no demand knobs (uniform over the active lines)"
	}
	return name + ": " + strings.Join(parts, " ")
}

// compactValue renders a knob value briefly enough for one banner line.
func compactValue(v interface{}) string {
	switch t := v.(type) {
	case map[string]interface{}:
		keys := make([]string, 0, len(t))
		for k := range t {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		parts := make([]string, 0, len(keys))
		for _, k := range keys {
			if n, ok := numberFromAny(t[k]); ok {
				parts = append(parts, k+":"+trimFloat(n))
			}
		}
		return "{" + strings.Join(parts, ",") + "}"
	case float64:
		return trimFloat(t)
	case int:
		return trimFloat(float64(t))
	default:
		return "?"
	}
}

// trimFloat renders a probability or a weight without the long tail of
// binary-float digits. 'g' with 4 significant digits covers both a 0.05
// probability and a weight of 60.
func trimFloat(f float64) string {
	return strconv.FormatFloat(f, 'g', 4, 64)
}
