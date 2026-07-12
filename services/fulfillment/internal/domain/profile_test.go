package domain

import "testing"

func TestSkeletonProfileMatchesDomain(t *testing.T) {
	profile := Profile()
	if profile.ServiceID != "fulfillment" {
		t.Fatalf("unexpected service id: %s", profile.ServiceID)
	}
	if profile.Domain != "Fulfillment" {
		t.Fatalf("unexpected domain: %s", profile.Domain)
	}
	if Health() != "ok" {
		t.Fatalf("unexpected health value")
	}
	// Verify REQ-018 is listed as a work package.
	found := false
	for _, wp := range profile.WorkPackages {
		if wp == "REQ-018" {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("REQ-018 not found in work packages: %v", profile.WorkPackages)
	}
}
