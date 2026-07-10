package domain

import (
	"testing"
	"time"
)

func publishedDelayProduct(t *testing.T, now time.Time) InsuranceProduct {
	t.Helper()
	premium, err := NewMoney("CNY", 500)
	if err != nil {
		t.Fatal(err)
	}
	limit, err := NewMoney("CNY", 3000)
	if err != nil {
		t.Fatal(err)
	}
	product, err := NewInsuranceProduct("ip-delay-v1", ProductDelayInsurance, "v1", premium, limit, now.Add(-time.Hour), now.Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	product, err = product.Publish(now)
	if err != nil {
		t.Fatal(err)
	}
	return product
}

func TestIssuePolicyProducesDeterministicPolicyNumberAndActiveStatus(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	policy, err := NewIssuedPolicy(PolicyIssueRequest{PolicyID: "pol-1", Product: publishedDelayProduct(t, now), JourneyOrderID: "jo-1", AncillaryOrderItemID: "anc-1", AccountID: "acct-1", TravelerRef: "trav-1", SegmentRefs: []string{"seg-b", "seg-a"}, PaymentIntentID: "pi-1", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour), Now: now})
	if err != nil {
		t.Fatalf("issue policy: %v", err)
	}
	if policy.Status != PolicyActive {
		t.Fatalf("status = %s, want ACTIVE", policy.Status)
	}
	if policy.PolicyNumber == "" {
		t.Fatal("policy number is required")
	}
	if got := policy.SegmentRefs; len(got) != 2 || got[0] != "seg-a" || got[1] != "seg-b" {
		t.Fatalf("segment refs not normalized: %#v", got)
	}
}

func TestClaimRejectsUnverifiedDelayFact(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	policy, err := NewIssuedPolicy(PolicyIssueRequest{PolicyID: "pol-1", Product: publishedDelayProduct(t, now), JourneyOrderID: "jo-1", AncillaryOrderItemID: "anc-1", AccountID: "acct-1", TravelerRef: "trav-1", SegmentRefs: []string{"seg-a"}, PaymentIntentID: "pi-1", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour), Now: now})
	if err != nil {
		t.Fatal(err)
	}
	amount, _ := NewMoney("CNY", 1000)
	_, err = OpenClaim("clm-1", policy, ClaimDelayAuto, "fact-1", &DelayFact{SourceEventType: "TrainDelayed", SourceEventID: "evt-1", DelayMinutes: 90}, "", nil, amount, now)
	if err == nil {
		t.Fatal("expected unsupported delay source error")
	}
}

func TestApprovedClaimCanSettleOnce(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	policy, err := NewIssuedPolicy(PolicyIssueRequest{PolicyID: "pol-1", Product: publishedDelayProduct(t, now), JourneyOrderID: "jo-1", AncillaryOrderItemID: "anc-1", AccountID: "acct-1", TravelerRef: "trav-1", SegmentRefs: []string{"seg-a"}, PaymentIntentID: "pi-1", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour), Now: now})
	if err != nil {
		t.Fatal(err)
	}
	amount, _ := NewMoney("CNY", 1000)
	claim, err := OpenClaim("clm-1", policy, ClaimDelayAuto, "fact-1", &DelayFact{SourceEventType: "SegmentArrived", SourceEventID: "evt-1", DelayMinutes: 90}, "", nil, amount, now)
	if err != nil {
		t.Fatal(err)
	}
	if claim.Status != ClaimApproved {
		t.Fatalf("claim status = %s", claim.Status)
	}
	advice, err := claim.Settle("pad-1", PayoutPaymentRefund, "DELAY_60")
	if err != nil {
		t.Fatal(err)
	}
	if advice.Status != "RECOMMENDED" || claim.Status != ClaimPayoutRecommended {
		t.Fatalf("unexpected settlement: %#v %#v", advice, claim)
	}
}
