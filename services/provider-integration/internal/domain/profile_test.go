package domain

import "testing"

func TestProfileMatchesProviderACLFoundation(t *testing.T) {
	profile := Profile()
	if profile.ServiceID != "provider-integration" {
		t.Fatalf("unexpected service id: %s", profile.ServiceID)
	}
	if profile.Domain != "Provider Integration" {
		t.Fatalf("unexpected domain: %s", profile.Domain)
	}
	if profile.Phase != "phase-1-acl-foundation" {
		t.Fatalf("unexpected phase: %s", profile.Phase)
	}
	if len(profile.WorkPackages) != 1 || profile.WorkPackages[0] != "REQ-015" {
		t.Fatalf("unexpected work packages: %#v", profile.WorkPackages)
	}
	if WorkPackageSummary != "REQ-015 Provider Integration ACL foundation" {
		t.Fatalf("unexpected work package summary: %s", WorkPackageSummary)
	}
	if Health() != "ok" {
		t.Fatalf("unexpected health value")
	}
}
