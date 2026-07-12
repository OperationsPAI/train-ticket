package main

import (
	"encoding/json"
	"testing"
)

func TestStatsRecordHTTP(t *testing.T) {
	s := NewStats()
	s.RecordHTTP("trip-planning", 200, 45.5)
	s.RecordHTTP("trip-planning", 200, 55.0)
	s.RecordHTTP("trip-planning", 500, 120.0)

	snap := s.Snapshot()
	if snap.HTTP["trip-planning:200"] != 2 {
		t.Errorf("expected 2 200s, got %d", snap.HTTP["trip-planning:200"])
	}
	if snap.HTTP["trip-planning:500"] != 1 {
		t.Errorf("expected 1 500, got %d", snap.HTTP["trip-planning:500"])
	}
	lat := snap.LatencyMs["trip-planning"]
	if lat.N != 3 {
		t.Errorf("expected 3 samples, got %d", lat.N)
	}
	if lat.P50 != 55.0 {
		t.Errorf("expected p50=55.0, got %f", lat.P50)
	}
}

func TestStatsRecordJourney(t *testing.T) {
	s := NewStats()
	s.RecordJourney("purchase:purchased")
	s.RecordJourney("purchase:purchased")
	s.RecordJourney("browse:browsed")

	snap := s.Snapshot()
	if snap.Journeys["purchase:purchased"] != 2 {
		t.Errorf("unexpected count: %d", snap.Journeys["purchase:purchased"])
	}
	if snap.Journeys["browse:browsed"] != 1 {
		t.Errorf("unexpected count: %d", snap.Journeys["browse:browsed"])
	}
}

func TestStatsJSON(t *testing.T) {
	s := NewStats()
	s.RecordHTTP("payment", 201, 30.0)
	s.RecordJourney("purchase:ok")
	s.RecordError("payment:timeout")
	s.ScalperAttempts.Add(5)
	s.ScalperSuccess.Add(3)

	jsonStr := s.JSON()
	var result map[string]interface{}
	if err := json.Unmarshal([]byte(jsonStr), &result); err != nil {
		t.Fatalf("invalid JSON: %v", err)
	}
	if _, ok := result["uptime_s"]; !ok {
		t.Error("missing uptime_s")
	}
	if _, ok := result["journeys"]; !ok {
		t.Error("missing journeys")
	}
	scalper, _ := result["scalper"].(map[string]interface{})
	if scalper == nil {
		t.Fatal("missing scalper")
	}
	if scalper["attempts"] != float64(5) {
		t.Errorf("unexpected attempts: %v", scalper["attempts"])
	}
}

func TestStatsLatencyPercentiles(t *testing.T) {
	s := NewStats()
	// Add 100 samples: 1, 2, 3, ..., 100
	for i := 1; i <= 100; i++ {
		s.RecordHTTP("test-svc", 200, float64(i))
	}

	snap := s.Snapshot()
	lat := snap.LatencyMs["test-svc"]
	if lat.N != 100 {
		t.Errorf("expected 100 samples, got %d", lat.N)
	}
	// p50 should be around 50
	if lat.P50 < 49 || lat.P50 > 51 {
		t.Errorf("unexpected p50: %f", lat.P50)
	}
	// p95 should be around 95
	if lat.P95 < 93 || lat.P95 > 96 {
		t.Errorf("unexpected p95: %f", lat.P95)
	}
	// p99 should be around 99
	if lat.P99 < 97 || lat.P99 > 100 {
		t.Errorf("unexpected p99: %f", lat.P99)
	}
	// max should be 100
	if lat.Max != 100.0 {
		t.Errorf("unexpected max: %f", lat.Max)
	}
}
