package main

import (
	"math/rand"
	"testing"
)

func TestProvidersDepartureDate(t *testing.T) {
	cfg := &Config{
		Bootstrap: BootstrapConfig{
			DepartureWindow: DepartureWindow{FromDays: 7, ToDays: 21},
		},
	}
	rng := rand.New(rand.NewSource(42))
	p := NewProviders(cfg, nil, NewRegistry(), NewStats(), rng)

	date := p.DepartureDate()
	if len(date) != 10 { // YYYY-MM-DD
		t.Errorf("unexpected date format: %s", date)
	}
}

func TestProvidersCityPairError(t *testing.T) {
	cfg := &Config{}
	rng := rand.New(rand.NewSource(42))
	reg := NewRegistry()
	// Only one place - should error
	reg.Places["BJS"] = "place-bjs"
	p := NewProviders(cfg, nil, reg, NewStats(), rng)

	_, _, _, _, err := p.CityPair()
	if err == nil {
		t.Error("expected error with fewer than 2 places")
	}
}

func TestProvidersCityPairSuccess(t *testing.T) {
	cfg := &Config{}
	rng := rand.New(rand.NewSource(42))
	reg := NewRegistry()
	reg.Places["BJS"] = "place-bjs"
	reg.Places["SHA"] = "place-sha"
	p := NewProviders(cfg, nil, reg, NewStats(), rng)

	oc, dc, op, dp, err := p.CityPair()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if oc == dc {
		t.Error("origin and dest codes should be different")
	}
	if op == "" || dp == "" {
		t.Error("place IDs should not be empty")
	}
}

func TestProvidersApplyPersona(t *testing.T) {
	cfg := &Config{
		Behavior: map[string]interface{}{
			"p_new_account": 0.35,
		},
		Journey: map[string]float64{
			"browse":   30,
			"purchase": 40,
		},
		Personas: map[string]PersonaConfig{
			"business": {
				Weight: 1.0,
				JourneyWeights: map[string]float64{
					"purchase": 80,
				},
				Overrides: map[string]interface{}{
					"p_new_account": 0.10,
				},
			},
		},
	}
	rng := rand.New(rand.NewSource(42))
	p := NewProviders(cfg, nil, NewRegistry(), NewStats(), rng)

	// Before persona
	if p.CtxFloat("p_new_account", 0) != 0.35 {
		t.Errorf("before persona: unexpected p_new_account: %f", p.CtxFloat("p_new_account", 0))
	}

	// Apply persona
	persona := &PersonaConfig{
		JourneyWeights: map[string]float64{"purchase": 80},
		Overrides:      map[string]interface{}{"p_new_account": 0.10},
	}
	p.ApplyPersona(persona)

	if p.CtxFloat("p_new_account", 0) != 0.10 {
		t.Errorf("after persona: unexpected p_new_account: %f", p.CtxFloat("p_new_account", 0))
	}
	if p.JourneyMix["purchase"] != 80 {
		t.Errorf("after persona: unexpected purchase weight: %f", p.JourneyMix["purchase"])
	}
	// browse should not be in the journey mix after persona override
	if _, ok := p.JourneyMix["browse"]; ok {
		t.Error("browse should not be in journey mix after persona override")
	}
}

func TestProvidersCtxMap(t *testing.T) {
	cfg := &Config{
		Behavior: map[string]interface{}{
			"channels": map[string]interface{}{
				"WEB": 0.8,
				"APP": 0.2,
			},
		},
	}
	rng := rand.New(rand.NewSource(42))
	p := NewProviders(cfg, nil, NewRegistry(), NewStats(), rng)

	channels := p.CtxMap("channels", nil)
	if channels == nil {
		t.Fatal("channels should not be nil")
	}
	if channels["WEB"] != 0.8 {
		t.Errorf("unexpected WEB weight: %f", channels["WEB"])
	}
	if channels["APP"] != 0.2 {
		t.Errorf("unexpected APP weight: %f", channels["APP"])
	}
}

func TestProvidersChance(t *testing.T) {
	cfg := &Config{
		Behavior: map[string]interface{}{
			"always_true":  1.0,
			"always_false": 0.0,
		},
	}
	rng := rand.New(rand.NewSource(42))
	p := NewProviders(cfg, nil, NewRegistry(), NewStats(), rng)

	// With probability 1.0, should always be true
	if !p.Chance("always_true") {
		t.Error("expected true for probability 1.0")
	}
	// With probability 0.0, should always be false
	if p.Chance("always_false") {
		t.Error("expected false for probability 0.0")
	}
}
