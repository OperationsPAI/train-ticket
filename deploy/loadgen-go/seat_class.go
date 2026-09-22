package main

import (
	"context"
	"sort"
)

// Seat class as a per-persona property of demand.
//
// The knob is `seat_classes`, read through the behavior context, so a value in
// `behavior:` is the default for every persona and a persona's `overrides:`
// replaces it. Written as a string literal at the CtxMap call site below, never
// as a named constant, because deployed_config_test.go's dead-key guard scrapes
// the literal argument of CtxFloat/CtxMap to decide whether a configured knob
// is actually read.
//
// Its keys are fare-pricing's own vocabulary: the six values of
// SeatClassMultiplier in services/fare-pricing/src/fare_pricing/domain.py.
// _seat_class_multiplier raises PricingError on a key outside that set, so an
// abbreviation such as "SECOND" is rejected outright rather than priced at 1.0.

// fareSeatClasses is the value set fare-pricing prices, with the multiplier it
// applies to the base fare. The multipliers are duplicated here only as
// documentation of what each class costs; the service is authoritative.
//
//	SECOND_CLASS    1.0
//	FIRST_CLASS     1.6
//	BUSINESS_CLASS  2.8
//	STANDING        0.7
//	SLEEPER_HARD    1.8
//	SLEEPER_SOFT    2.5
var fareSeatClasses = map[string]bool{
	"SECOND_CLASS":   true,
	"FIRST_CLASS":    true,
	"BUSINESS_CLASS": true,
	"STANDING":       true,
	"SLEEPER_HARD":   true,
	"SLEEPER_SOFT":   true,
}

// seatAssignmentClasses is the subset seat-assignment stocks seats for. Its
// seat map is built from DefaultCRH380AConfig, whose coaches carry only these
// three car types, and filterAvailable matches a seat on exact equality with
// the normalized class.
//
// STANDING and the two sleeper classes are deliberately absent. A measured
// request for each of the six against the assignment engine returns:
//
//	SECOND_CLASS    1 seat
//	FIRST_CLASS     1 seat
//	BUSINESS_CLASS  1 seat
//	STANDING        0 seats, "no matching seat available"
//	SLEEPER_HARD    0 seats, "no matching seat available"
//	SLEEPER_SOFT    0 seats, "no matching seat available"
//
// So these three are never sent to seat-assignment, and the reason is the
// domain rather than the empty result: a standing ticket is by definition a
// ticket without a seat, and seat-assignment models STANDING as an OUTCOME of
// allocation (AllocationTypeStanding, DegradationStandingAssigned, applied when
// no compatible unit exists and the traveller accepted standing) and never as a
// class a caller may request. Asking a seat map for a standing seat would
// simulate a user action that does not exist, and it would 422 on every single
// call rather than when the train is genuinely full. A sold-out train still
// produces that 422 through the stocked classes, which is the honest way to
// reach it.
var seatAssignmentClasses = map[string]bool{
	"SECOND_CLASS":   true,
	"FIRST_CLASS":    true,
	"BUSINESS_CLASS": true,
}

// defaultSeatClassMix is the mix for a run whose config sets no seat_classes
// anywhere. Weighted towards second class, with every class represented so a
// bare config still exercises the whole fare range.
var defaultSeatClassMix = map[string]float64{
	"SECOND_CLASS": 0.70, "FIRST_CLASS": 0.18, "BUSINESS_CLASS": 0.05,
	"STANDING": 0.03, "SLEEPER_HARD": 0.03, "SLEEPER_SOFT": 0.01,
}

// SeatClass draws the seat class for one journey from the active persona's mix.
//
// A class the vocabulary does not contain is a configuration error that must
// not silently become second class: fare-pricing would reject the quote with
// PricingError, so the run would lose that share of its demand to a failure
// whose cause is in the ConfigMap. It is counted and the journey draws nothing.
func (p *Providers) SeatClass() (string, bool) {
	mix := p.CtxMap("seat_classes", defaultSeatClassMix)
	class := WeightedChoice(p.Rng, mix)
	if !fareSeatClasses[class] {
		p.Stats.RecordError("seat_class:unknown:" + class)
		return "", false
	}
	return class, true
}

// SeatClassOrDefault is SeatClass for the callers that have no branch to take
// when the configured vocabulary is wrong. Second class is what fare-pricing
// itself defaults seatClass to, so the quote still prices.
func (p *Providers) SeatClassOrDefault() string {
	if class, ok := p.SeatClass(); ok {
		return class
	}
	return "SECOND_CLASS"
}

// SeatAssignmentClasses lists the classes seat-assignment stocks, sorted so the
// startup banner and the tests read the same order every run.
func SeatAssignmentClasses() []string {
	out := make([]string, 0, len(seatAssignmentClasses))
	for class := range seatAssignmentClasses {
		out = append(out, class)
	}
	sort.Strings(out)
	return out
}

// AssignSeat asks seat-assignment for a seat of the drawn class.
//
// Returns the assignment id, or empty when the class is one seat-assignment
// does not stock, or when the train has no seat of that class left.
//
// A 422 is an expected answer rather than a fault: it is what seat-assignment
// returns once every seat of the class is held, and under load that is a normal
// outcome of the day. It is passed to PollRequest as expected so it stays out
// of the error tally, and recorded as a journey outcome so a run that could not
// seat its customers is visible in the stats.
func (p *Providers) AssignSeat(ctx context.Context, segmentRef, departureDate string,
	travelers []string, seatClass string) string {
	if !seatAssignmentClasses[seatClass] {
		// Priced by fare-pricing, carries no seat. Not a failure.
		p.Stats.RecordJourney("seat_assignment:no_seat_for_class:" + seatClass)
		return ""
	}
	if segmentRef == "" || departureDate == "" || len(travelers) == 0 {
		return ""
	}
	body := map[string]interface{}{
		"segmentRef":    segmentRef,
		"departureDate": departureDate,
		"travelerRefs":  travelers,
		"seatClass":     seatClass,
	}
	if p.Rng.Float64() < p.Cfg.Staff.PSeatPreferences {
		body["preferences"] = []map[string]interface{}{{"preferenceType": "WINDOW", "priority": 1}}
	}
	code, data, err := p.API.PollRequest(ctx, "POST", "seat-assignment",
		"/api/v1/seat-assignments", body, nil, []int{200, 201}, "seat-assign", []int{422})
	if err != nil || code == 422 {
		if code == 422 {
			p.Stats.RecordJourney("seat_assignment:class_sold_out:" + seatClass)
		}
		return ""
	}
	assignments, _ := data["assignments"].([]interface{})
	if len(assignments) == 0 {
		return ""
	}
	first, _ := assignments[0].(map[string]interface{})
	return getString(first, "assignmentId")
}
