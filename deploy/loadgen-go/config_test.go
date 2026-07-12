package main

import (
	"math/rand"
	"os"
	"testing"
)

func TestLoadConfig(t *testing.T) {
	yaml := `
target:
  base_url_template: "http://{service}:8080"
  redis_url: "redis://localhost:6379"
  request_timeout_seconds: 5

run:
  mode: closed-loop
  workers: 100
  duration_seconds: 60
  think_time_seconds: { min: 0.1, max: 1.0 }
  session_pause_seconds: { min: 0.5, max: 2.0 }
  stats_interval_seconds: 10
  state_file: "/tmp/test-state.json"

journey_mix:
  browse: 30
  purchase: 40
  refund: 10

behavior:
  p_new_account: 0.50
  p_abandon_after_search: 0.10
  channels: { WEB: 0.8, APP: 0.2 }

staff:
  workers: 4
  think_time_seconds: { min: 0.1, max: 0.5 }
  queue_poll_seconds: 0.2
  p_risk_approve: 0.60

bootstrap:
  enabled: true
  cities:
    - { name: Beijing, code: BJS }
    - { name: Shanghai, code: SHA }
  departure_window:
    from_days: 7
    to_days: 14
  services_per_date: 2
  service_number_base: 6000

defaults:
  currency: "USD"
  insurance_premium_minor: 30000

scalper:
  enabled: false
  workers: 1

polling:
  attempts: 10
  interval_seconds: 2

ops:
  enabled: true
  interval_seconds: { min: 60, max: 120 }
  dashboard_id: dash-test
  p_supplier_catalog_write: 0.10
  p_wallet_manual_issue: 0.10
  wallet_manual_issue_minor_units: 50
`
	tmpFile, err := os.CreateTemp("", "config-*.yaml")
	if err != nil {
		t.Fatal(err)
	}
	defer os.Remove(tmpFile.Name())
	if _, err := tmpFile.WriteString(yaml); err != nil {
		t.Fatal(err)
	}
	tmpFile.Close()

	cfg, err := LoadConfig(tmpFile.Name())
	if err != nil {
		t.Fatalf("LoadConfig failed: %v", err)
	}

	if cfg.Target.BaseURLTemplate != "http://{service}:8080" {
		t.Errorf("unexpected base_url_template: %s", cfg.Target.BaseURLTemplate)
	}
	if cfg.Run.Workers != 100 {
		t.Errorf("unexpected workers: %d", cfg.Run.Workers)
	}
	if cfg.Run.Mode != "closed-loop" {
		t.Errorf("unexpected mode: %s", cfg.Run.Mode)
	}
	if cfg.Run.DurationSeconds != 60 {
		t.Errorf("unexpected duration: %f", cfg.Run.DurationSeconds)
	}
	if cfg.Run.ThinkTime.Min != 0.1 || cfg.Run.ThinkTime.Max != 1.0 {
		t.Errorf("unexpected think time: %+v", cfg.Run.ThinkTime)
	}
	if len(cfg.Journey) != 3 {
		t.Errorf("unexpected journey_mix length: %d", len(cfg.Journey))
	}
	if cfg.Journey["purchase"] != 40 {
		t.Errorf("unexpected purchase weight: %f", cfg.Journey["purchase"])
	}
	if cfg.BehaviorFloat("p_new_account", 0) != 0.50 {
		t.Errorf("unexpected p_new_account: %f", cfg.BehaviorFloat("p_new_account", 0))
	}
	channels := cfg.BehaviorMap("channels", nil)
	if channels == nil || channels["WEB"] != 0.8 {
		t.Errorf("unexpected channels: %v", channels)
	}
	if cfg.Staff.Workers != 4 {
		t.Errorf("unexpected staff workers: %d", cfg.Staff.Workers)
	}
	if cfg.Staff.PRiskApprove != 0.60 {
		t.Errorf("unexpected p_risk_approve: %f", cfg.Staff.PRiskApprove)
	}
	if len(cfg.Bootstrap.Cities) != 2 {
		t.Errorf("unexpected cities count: %d", len(cfg.Bootstrap.Cities))
	}
	if cfg.Bootstrap.DepartureWindow.FromDays != 7 {
		t.Errorf("unexpected from_days: %d", cfg.Bootstrap.DepartureWindow.FromDays)
	}
	if cfg.Currency() != "USD" {
		t.Errorf("unexpected currency: %s", cfg.Currency())
	}
	if cfg.DefaultInt("insurance_premium_minor", 0) != 30000 {
		t.Errorf("unexpected insurance_premium_minor: %d", cfg.DefaultInt("insurance_premium_minor", 0))
	}
	if cfg.Polling.Attempts != 10 {
		t.Errorf("unexpected polling attempts: %d", cfg.Polling.Attempts)
	}
	if !cfg.Ops.Enabled {
		t.Error("ops should be enabled")
	}
	if cfg.Scalper.Enabled {
		t.Error("scalper should be disabled")
	}
}

func TestWeightedChoice(t *testing.T) {
	rng := rand.New(rand.NewSource(42))
	weights := map[string]float64{
		"a": 70,
		"b": 20,
		"c": 10,
	}
	counts := map[string]int{}
	for i := 0; i < 10000; i++ {
		counts[WeightedChoice(rng, weights)]++
	}
	// With 10000 samples, "a" should appear roughly 7000 times
	if counts["a"] < 6000 || counts["a"] > 8000 {
		t.Errorf("unexpected distribution for 'a': %d", counts["a"])
	}
	if counts["b"] < 1000 || counts["b"] > 3000 {
		t.Errorf("unexpected distribution for 'b': %d", counts["b"])
	}
}

func TestUUID7(t *testing.T) {
	id := UUID7()
	if len(id) != 36 {
		t.Errorf("unexpected UUID7 length: %d", len(id))
	}
	// Should have 4 dashes
	dashes := 0
	for _, c := range id {
		if c == '-' {
			dashes++
		}
	}
	if dashes != 4 {
		t.Errorf("unexpected dash count: %d", dashes)
	}
	// Version nibble should be 7
	if id[14] != '7' {
		t.Errorf("unexpected version nibble: %c", id[14])
	}
}

func TestRandName(t *testing.T) {
	rng := rand.New(rand.NewSource(99))
	given, family := RandName(rng)
	if given == "" || family == "" {
		t.Error("empty name generated")
	}
	if len(family) < 6 { // at least "X-xxxx"
		t.Errorf("family name too short: %s", family)
	}
}

func TestConvertTemplate(t *testing.T) {
	input := "http://{service}:8080"
	expected := "http://%s:8080"
	if got := ConvertTemplate(input); got != expected {
		t.Errorf("ConvertTemplate(%q) = %q, want %q", input, got, expected)
	}
}

func TestComputeDepartureDates(t *testing.T) {
	cfg := &Config{
		Bootstrap: BootstrapConfig{
			DepartureWindow: DepartureWindow{FromDays: 7, ToDays: 10},
		},
	}
	dates := ComputeDepartureDates(cfg)
	if len(dates) != 4 { // 7, 8, 9, 10
		t.Errorf("unexpected dates count: %d, want 4", len(dates))
	}
}
