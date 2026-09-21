package main

import (
	"sort"
	"testing"
)

// Every journey the deployed configuration names is a journey the deployed
// configuration can actually select, and every journey it selects is one
// executeJourney can dispatch.
//
// The defect these pin was invisible from either side alone. `journey_mix`
// named fifteen journeys and each of the three personas named a subset, and
// ApplyPersona REPLACES the mix rather than merging into it, so legacy,
// transfer, disruption, group_booking and campaign had weight zero under the
// deployed profile. Five of fifteen journeys never executed while the config
// file read as though they were weighted, and no test noticed because both
// halves were internally consistent.

// effectiveJourneyWeights is the mix a run actually draws from, per persona.
// Mirrors ApplyPersona: a persona with its own weights replaces the global map
// entirely, and one without inherits it.
func effectiveJourneyWeights(cfg *Config) map[string]map[string]float64 {
	out := make(map[string]map[string]float64, len(cfg.Personas))
	if len(cfg.Personas) == 0 {
		out[""] = cfg.Journey
		return out
	}
	for name, persona := range cfg.Personas {
		if persona.JourneyWeights != nil {
			out[name] = persona.JourneyWeights
			continue
		}
		out[name] = cfg.Journey
	}
	return out
}

// reachableJourneys is every journey some persona can draw with a weight above
// zero.
func reachableJourneys(cfg *Config) map[string]bool {
	reachable := make(map[string]bool)
	for _, weights := range effectiveJourneyWeights(cfg) {
		for journey, weight := range weights {
			if weight > 0 {
				reachable[journey] = true
			}
		}
	}
	return reachable
}

func TestEveryJourneyTheMixNamesIsReachable(t *testing.T) {
	cfg := requireDeployedConfig(t)
	reachable := reachableJourneys(cfg)

	var unreachable []string
	for journey, weight := range cfg.Journey {
		if weight > 0 && !reachable[journey] {
			unreachable = append(unreachable, journey)
		}
	}
	sort.Strings(unreachable)
	if len(unreachable) > 0 {
		t.Fatalf("journey_mix weights %v, and no persona does: personas replace the global "+
			"mix, so these never run. Give each one a weight in the persona whose demand it "+
			"belongs to, or move it off the customer mix if no customer performs it.",
			unreachable)
	}
}

func TestEveryReachableJourneyCanBeDispatched(t *testing.T) {
	cfg := requireDeployedConfig(t)

	// executeJourney answers "unknown_journey" for a name it has no case for,
	// and a journey drawn under that name burns its share of the offered load
	// on a switch default.
	//
	// Read off the source rather than by calling executeJourney, which would
	// run the journey against a live cluster. dispatchableJourneys is the
	// switch's own case list, and TestDispatchableJourneysMatchTheSwitch below
	// is what keeps the two from drifting.
	for journey := range reachableJourneys(cfg) {
		if !dispatchableJourneys[journey] {
			t.Errorf("persona weights select %q and executeJourney cannot dispatch it", journey)
		}
	}
}

// TestDispatchableJourneysMatchTheSwitch keeps the list above honest.
//
// A journey added to executeJourney and not to the list would read as
// undispatchable; one removed from the switch and left in the list would read
// as dispatchable and fall to the default at runtime. Both are caught by
// asking executeJourney for a name that is in neither.
func TestDispatchableJourneysMatchTheSwitch(t *testing.T) {
	outcome, err := executeJourney(nil, nil, "a-journey-no-case-names")
	if err != nil {
		t.Fatalf("dispatching an unknown journey errored: %v", err)
	}
	if outcome != "unknown_journey" {
		t.Fatalf("an unknown journey answered %q, want unknown_journey", outcome)
	}
	if dispatchableJourneys["a-journey-no-case-names"] {
		t.Fatal("dispatchableJourneys claims a name executeJourney has no case for")
	}
}

func TestPersonaWeightsSumAboveZero(t *testing.T) {
	cfg := requireDeployedConfig(t)

	// A persona whose own journey weights are all zero draws nothing and its
	// population share is silently redistributed by WeightedChoice.
	for name, weights := range effectiveJourneyWeights(cfg) {
		total := 0.0
		for _, weight := range weights {
			total += weight
		}
		if total <= 0 {
			t.Errorf("persona %q has no journey with a positive weight", name)
		}
	}
}
