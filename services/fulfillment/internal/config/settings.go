// Package config reads the settings the release ships beside the binary.
//
// The values are the release's, not this service's: they live in
// services/fulfillment/config, which the image carries at /app/config, so a
// deployment states them by changing a document it owns instead of a rebuild.
// A key this reader does not know is an error, because a setting the service
// never reads is a release change that would otherwise do nothing at all.
package config

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"time"
)

// DefaultUsageSettingsPath is where the image carries the document.
const DefaultUsageSettingsPath = "/app/config/usage.properties"

// UsageSettings is what the release states about the use of a segment.
type UsageSettings struct {
	// MinimumSegmentSeconds is the shortest time a segment this release sells
	// takes from its boarding to its arrival. Zero accepts a completion from
	// the moment the record is boarded, which is what the service did before
	// the value was stated here.
	MinimumSegmentSeconds int64
}

// SegmentUsageFloor is the floor the release states, as a duration.
func (s UsageSettings) SegmentUsageFloor() time.Duration {
	return time.Duration(s.MinimumSegmentSeconds) * time.Second
}

// LoadUsageSettings reads the settings document at path.
func LoadUsageSettings(path string) (UsageSettings, error) {
	document, err := os.Open(path)
	if err != nil {
		return UsageSettings{}, fmt.Errorf("open usage settings: %w", err)
	}
	defer func() { _ = document.Close() }()
	settings, err := ParseUsageSettings(document)
	if err != nil {
		return UsageSettings{}, fmt.Errorf("read usage settings %s: %w", path, err)
	}
	return settings, nil
}

// ParseUsageSettings reads the `key=value` document. Blank lines and `#`
// comments, whole-line or trailing, are the release's notes and carry no value.
func ParseUsageSettings(document io.Reader) (UsageSettings, error) {
	var settings UsageSettings
	scanner := bufio.NewScanner(document)
	for line := 1; scanner.Scan(); line++ {
		text, _, _ := strings.Cut(scanner.Text(), "#")
		text = strings.TrimSpace(text)
		if text == "" {
			continue
		}
		key, value, found := strings.Cut(text, "=")
		key, value = strings.TrimSpace(key), strings.TrimSpace(value)
		if !found || key == "" {
			return UsageSettings{}, fmt.Errorf("line %d: expected a key and a value, got %q", line, text)
		}
		switch key {
		case "usage.minimumSegmentSeconds":
			seconds, err := strconv.ParseInt(value, 10, 64)
			if err != nil {
				return UsageSettings{}, fmt.Errorf("line %d: %s is not a whole number of seconds: %q", line, key, value)
			}
			if seconds < 0 {
				return UsageSettings{}, fmt.Errorf("line %d: %s is negative: %d", line, key, seconds)
			}
			settings.MinimumSegmentSeconds = seconds
		default:
			return UsageSettings{}, fmt.Errorf("line %d: unknown setting %q", line, key)
		}
	}
	if err := scanner.Err(); err != nil {
		return UsageSettings{}, fmt.Errorf("read usage settings: %w", err)
	}
	return settings, nil
}
