package config

import "testing"

// The settings file travels inside the image, so the reading is pinned here: a
// file the release cannot parse has to stop the process at startup rather than
// surface later as a window nobody can explain.
func TestLoadServedGraphReadsTheEmbeddedSettings(t *testing.T) {
	served, err := LoadServedGraph()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if served.SettleSeconds < 0 {
		t.Fatalf("the served graph window must not be negative, got %d", served.SettleSeconds)
	}
}
