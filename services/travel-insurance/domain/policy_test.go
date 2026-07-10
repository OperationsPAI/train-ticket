package domain

import (
	"testing"
	"time"
)

func TestPolicyIssuanceActivatesWithImmutablePolicyNumber(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	product := DefaultCatalog(now)[0]
	policy, err := NewPolicy(IssuePolicySpec{Product: product, JourneyOrderID: "ord-1", AccountID: "acct-1", TravelerRef: "tvl-1", SegmentRefs: []string{"seg-b", "seg-a"}, CoverageStartAt: now.Add(-time.Hour), CoverageEndAt: now.Add(time.Hour)}, now)
	if err != nil {
		t.Fatalf("new policy: %v", err)
	}
	if err := policy.RequestUnderwriting(now); err != nil {
		t.Fatalf("request underwriting: %v", err)
	}
	if err := policy.ApplyUnderwritingSucceeded("SIM-TI-123", now); err != nil {
		t.Fatalf("apply underwriting: %v", err)
	}
	if policy.Status != PolicyActive {
		t.Fatalf("expected active policy, got %s", policy.Status)
	}
	if policy.PolicyNumber != "SIM-TI-123" {
		t.Fatalf("policy number mismatch")
	}
	if got := policy.SegmentRefs[0]; got != "seg-a" {
		t.Fatalf("segments should be normalized, first=%s", got)
	}
}

func TestClaimRulesRequireEvidenceAndLimitAmount(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	product := DefaultCatalog(now)[0]
	policy, err := NewPolicy(IssuePolicySpec{Product: product, JourneyOrderID: "ord-1", AccountID: "acct-1", TravelerRef: "tvl-1", SegmentRefs: []string{"seg-1"}, CoverageStartAt: now.Add(-time.Hour), CoverageEndAt: now.Add(time.Hour)}, now)
	if err != nil {
		t.Fatal(err)
	}
	policy.Status = PolicyActive
	_, err = NewClaim(ClaimSpec{Policy: policy, ClaimType: ClaimDelayAuto, TriggerFactKey: "fact-1", ClaimedAmount: Money{Currency: "CNY", MinorUnits: 1000}}, now)
	if err == nil {
		t.Fatalf("expected missing evidence to fail")
	}
	_, err = NewClaim(ClaimSpec{Policy: policy, ClaimType: ClaimDelayAuto, TriggerFactKey: "fact-1", EvidenceRefs: []string{"evt-1"}, ClaimedAmount: Money{Currency: "CNY", MinorUnits: product.CoverageLimit.MinorUnits + 1}}, now)
	if err == nil {
		t.Fatalf("expected over-limit claim to fail")
	}
}
