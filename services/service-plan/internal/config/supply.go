package config

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"strings"
)

// Supply states how the segment supply facts are published. The interval end
// and the stop sequence digest are values the release states, so a caller
// reads the bound the service publishes instead of assuming one.
type Supply struct {
	IntervalBound      string `json:"intervalBound"`
	StopSequenceDigest string `json:"stopSequenceDigest"`
}

const (
	// IntervalBoundInclusive publishes the end as the last stop the interval covers.
	IntervalBoundInclusive = "INCLUSIVE"
	// IntervalBoundExclusive publishes the end as the first position past the interval.
	IntervalBoundExclusive = "EXCLUSIVE"
	// DigSchemeSHA256 is the digest scheme the supply contract publishes.
	DigSchemeSHA256 = "sha256"
)

//go:embed supply.json
var shipped []byte

// ShippedSupply reads the settings the service image carries at build time.
func ShippedSupply() (Supply, error) {
	var settings Supply
	if err := json.Unmarshal(shipped, &settings); err != nil {
		return Supply{}, fmt.Errorf("supply settings: %w", err)
	}
	settings.IntervalBound = strings.ToUpper(strings.TrimSpace(settings.IntervalBound))
	settings.StopSequenceDigest = strings.ToLower(strings.TrimSpace(settings.StopSequenceDigest))
	if settings.IntervalBound != IntervalBoundInclusive && settings.IntervalBound != IntervalBoundExclusive {
		return Supply{}, fmt.Errorf("supply settings: unsupported intervalBound %q", settings.IntervalBound)
	}
	if settings.StopSequenceDigest != DigSchemeSHA256 {
		return Supply{}, fmt.Errorf("supply settings: unsupported stopSequenceDigest %q", settings.StopSequenceDigest)
	}
	return settings, nil
}
