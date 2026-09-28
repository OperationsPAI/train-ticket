package config

import "testing"

func TestFromEnvKeepsTheDefaultWhenAReleaseNamesNoPage(t *testing.T) {
	t.Setenv("SERVICE_PLAN_LISTING_PAGE_LIMIT", "")
	if got := FromEnv().ListingPageLimit; got != DefaultListingPageLimit {
		t.Fatalf("page a release named none: got %d, want %d", got, DefaultListingPageLimit)
	}
}

func TestFromEnvReadsThePageAReleaseSets(t *testing.T) {
	t.Setenv("SERVICE_PLAN_LISTING_PAGE_LIMIT", "40")
	if got := FromEnv().ListingPageLimit; got != 40 {
		t.Fatalf("page a release set: got %d, want 40", got)
	}
}

func TestFromEnvKeepsTheDefaultForAPageThatIsNotANumber(t *testing.T) {
	t.Setenv("SERVICE_PLAN_LISTING_PAGE_LIMIT", "twenty rows")
	if got := FromEnv().ListingPageLimit; got != DefaultListingPageLimit {
		t.Fatalf("page with a unit: got %d, want %d", got, DefaultListingPageLimit)
	}
}

func TestDescribeNamesTheResolvedSettings(t *testing.T) {
	t.Setenv("PORT", "9090")
	t.Setenv("SERVICE_PLAN_LISTING_PAGE_LIMIT", "40")
	if got := FromEnv().Describe(); got != "port=9090 listingPageLimit=40" {
		t.Fatalf("describe: got %q", got)
	}
}
