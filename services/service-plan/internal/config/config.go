// Package config holds what this service reads from its runtime, resolved in one
// place so that the process, its read paths and the tests read the same numbers.
//
// A setting is a release decision. The chart's services.service-plan.env block
// declares it, the image ships a default for it, and a value that is absent or
// blank resolves to that default, which is the behaviour this service had
// before the setting existed.
package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

const (
	// DefaultPort is the port the image listens on when a release names none.
	DefaultPort = 8080
	// DefaultListingPageLimit is the largest page a listing serves when a
	// release names none: the bound the listing shipped with.
	DefaultListingPageLimit = 100
)

// Settings is what one process resolved from its environment: the port it
// serves on and the largest page its segment listing answers with.
type Settings struct {
	Port             int
	ListingPageLimit int
}

// FromEnv resolves this process's settings.
func FromEnv() Settings {
	return Settings{
		Port:             positiveInt("PORT", DefaultPort),
		ListingPageLimit: positiveInt("SERVICE_PLAN_LISTING_PAGE_LIMIT", DefaultListingPageLimit),
	}
}

// Describe names the settings a process resolved, for the startup line that
// answers which release a pod is running.
func (s Settings) Describe() string {
	return fmt.Sprintf("port=%d listingPageLimit=%d", s.Port, s.ListingPageLimit)
}

// positiveInt reads a whole number a release may have set. A value that is
// absent, blank or not positive leaves the default in place rather than taking
// the service down over a setting.
func positiveInt(name string, fallback int) int {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value <= 0 {
		return fallback
	}
	return value
}
