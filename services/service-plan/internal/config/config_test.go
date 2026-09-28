package config

import (
	"strconv"
	"testing"
)

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

func TestFromEnvReadsTheBoundTheReleaseDeclares(t *testing.T) {
	// The chart declares it in the service-plan release's env block.
	t.Setenv("SERVICE_PLAN_LISTING_PAGE_LIMIT", "20")
	if got := FromEnv().ListingPageLimit; got != 20 {
		t.Fatalf("release page bound: got %d, want 20", got)
	}
}

func TestFromEnvReadsThePageBoundTheReleaseVaries(t *testing.T) {
	for _, bound := range []int{5, 100, 250} {
		t.Setenv("SERVICE_PLAN_LISTING_PAGE_LIMIT", strconv.Itoa(bound))
		if got := FromEnv().ListingPageLimit; got != bound {
			t.Fatalf("page bound %d: got %d", bound, got)
		}
	}
}

func TestFromEnvReadsAPageBoundAboveTheShippingPage(t *testing.T) {
	t.Setenv("SERVICE_PLAN_LISTING_PAGE_LIMIT", "250")
	if got := FromEnv().ListingPageLimit; got != 250 {
		t.Fatalf("a release may set the bound itself, got %d", got)
	}
}

func TestFromEnvReadsABlankPageBoundAsUnset(t *testing.T) {
	t.Setenv("SERVICE_PLAN_LISTING_PAGE_LIMIT", "   ")
	if got := FromEnv().ListingPageLimit; got != DefaultListingPageLimit {
		t.Fatalf("a blank page bound must leave the default, got %d", got)
	}
}

func TestFromEnvRefusesAPageBoundWithAUnit(t *testing.T) {
	t.Setenv("SERVICE_PLAN_LISTING_PAGE_LIMIT", "40 pages")
	if got := FromEnv().ListingPageLimit; got != DefaultListingPageLimit {
		t.Fatalf("a page bound with a unit must leave the default, got %d", got)
	}
}
