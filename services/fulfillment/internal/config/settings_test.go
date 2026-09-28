package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestParseUsageSettingsReadsTheFloorTheReleaseStates(t *testing.T) {
	document := strings.Join([]string{
		"# the release's usage settings",
		"",
		"usage.minimumSegmentSeconds=1800   # half an hour",
	}, "\n")
	settings, err := ParseUsageSettings(strings.NewReader(document))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if settings.MinimumSegmentSeconds != 1800 {
		t.Fatalf("minimumSegmentSeconds = %d, want 1800", settings.MinimumSegmentSeconds)
	}
	if settings.SegmentUsageFloor() != 30*time.Minute {
		t.Fatalf("segment usage floor = %s, want 30m", settings.SegmentUsageFloor())
	}
}

func TestParseUsageSettingsAcceptsNoFloor(t *testing.T) {
	settings, err := ParseUsageSettings(strings.NewReader("usage.minimumSegmentSeconds=0\n"))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if settings.SegmentUsageFloor() != 0 {
		t.Fatalf("segment usage floor = %s, want none", settings.SegmentUsageFloor())
	}
}

func TestParseUsageSettingsNamesWhatItCannotRead(t *testing.T) {
	for _, testCase := range []struct {
		name     string
		document string
		detail   string
	}{
		{name: "an unknown setting", document: "usage.minimumSegmentMinutes=30\n", detail: "unknown setting"},
		{name: "a value that is not a number", document: "usage.minimumSegmentSeconds=half an hour\n", detail: "not a whole number"},
		{name: "a negative floor", document: "usage.minimumSegmentSeconds=-1\n", detail: "negative"},
		{name: "a line without a value", document: "usage.minimumSegmentSeconds\n", detail: "expected a key and a value"},
	} {
		if _, err := ParseUsageSettings(strings.NewReader(testCase.document)); err == nil {
			t.Fatalf("%s: expected an error", testCase.name)
		} else if !strings.Contains(err.Error(), testCase.detail) {
			t.Fatalf("%s: error %q does not name the problem (%q)", testCase.name, err, testCase.detail)
		}
	}
}

func TestLoadUsageSettingsReadsTheDocumentItIsGiven(t *testing.T) {
	path := filepath.Join(t.TempDir(), "usage.properties")
	if err := os.WriteFile(path, []byte("usage.minimumSegmentSeconds=900\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	settings, err := LoadUsageSettings(path)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if settings.MinimumSegmentSeconds != 900 {
		t.Fatalf("minimumSegmentSeconds = %d, want 900", settings.MinimumSegmentSeconds)
	}
}

func TestUsageSettingsPathPrefersTheDeployment(t *testing.T) {
	t.Setenv(UsageSettingsPathEnv, "")
	if path := UsageSettingsPath(); path != DefaultUsageSettingsPath {
		t.Fatalf("path = %q, want the image's document %q", path, DefaultUsageSettingsPath)
	}
	t.Setenv(UsageSettingsPathEnv, "/etc/fulfillment/usage.properties")
	if path := UsageSettingsPath(); path != "/etc/fulfillment/usage.properties" {
		t.Fatalf("path = %q, want the deployment's document", path)
	}
}

func TestShippedUsageSettingsStateTheFloorTheReleaseUses(t *testing.T) {
	settings, err := LoadUsageSettings(filepath.Join("..", "..", "config", "usage.properties"))
	if err != nil {
		t.Fatalf("the image's own document does not read: %v", err)
	}
	if settings.SegmentUsageFloor() <= 0 {
		t.Fatalf("the image ships no usage floor: %s", settings.SegmentUsageFloor())
	}
}
