package config

import "testing"

func TestFromEnvKeepsTheShippingDefaults(t *testing.T) {
	t.Setenv("PORT", "")
	t.Setenv("SERVICE_PLAN_LISTING_PAGE_LIMIT", "")
	settings := FromEnv()
	if settings.Port != DefaultPort {
		t.Fatalf("port: got %d, want %d", settings.Port, DefaultPort)
	}
	if settings.ListingPageLimit != DefaultListingPageLimit {
		t.Fatalf("listing page bound: got %d, want %d", settings.ListingPageLimit, DefaultListingPageLimit)
	}
}

func TestFromEnvReadsThePageBoundAReleaseSets(t *testing.T) {
	t.Setenv("SERVICE_PLAN_LISTING_PAGE_LIMIT", "40")
	if got := FromEnv().ListingPageLimit; got != 40 {
		t.Fatalf("listing page bound: got %d, want 40", got)
	}
}

func TestFromEnvRefusesAPageBoundWithAUnit(t *testing.T) {
	t.Setenv("SERVICE_PLAN_LISTING_PAGE_LIMIT", "40 pages")
	if got := FromEnv().ListingPageLimit; got != DefaultListingPageLimit {
		t.Fatalf("a page bound with a unit must leave the default, got %d", got)
	}
}
