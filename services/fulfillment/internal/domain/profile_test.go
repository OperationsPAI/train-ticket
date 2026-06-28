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
}
