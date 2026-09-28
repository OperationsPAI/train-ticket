package config

import "testing"

// The settings file travels inside the image, so the reading is pinned here:
// a file the release cannot parse has to stop the process at startup rather
// than surface later as an allowance nobody can explain.
func TestLoadWayfindingReadsTheEmbeddedSettings(t *testing.T) {
	wayfinding, err := LoadWayfinding()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if wayfinding.StationAccessTimeMinutes < 0 {
		t.Fatalf("station allowance must not be negative, got %d", wayfinding.StationAccessTimeMinutes)
	}
}
