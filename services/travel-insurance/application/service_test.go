package application

import (
	"context"
	"testing"
	"time"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

type fixedClock struct{ t time.Time }

func (c fixedClock) Now() time.Time { return c.t }

type capturePublisher struct{ events []kitmsg.EventEnvelope }

func (p *capturePublisher) Publish(_ context.Context, e kitmsg.EventEnvelope) error {
	p.events = append(p.events, e)
	return nil
}

func TestIssuePolicyPublishesPolicyIssued(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	pub := &capturePublisher{}
	svc := NewInsuranceService(ServiceConfig{Publisher: pub, Clock: fixedClock{now}, IDGenerator: sequentialIDs()})
	policy, err := svc.IssuePolicy(context.Background(), IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: "jo-1", AncillaryOrderItemID: "anc-1", AccountID: "acct-1", TravelerRef: "trav-1", SegmentRefs: []string{"seg-1"}, PaymentIntentID: "pi-1", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour)})
	if err != nil {
		t.Fatalf("IssuePolicy: %v", err)
	}
	if policy.ID != "pol-1" {
		t.Fatalf("policy id = %s", policy.ID)
	}
	if len(pub.events) != 1 || pub.events[0].EventType != "PolicyIssued" {
		t.Fatalf("events = %#v", pub.events)
	}
}

func TestDuplicatePolicyIssueReturnsExistingPolicy(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	svc := NewInsuranceService(ServiceConfig{Clock: fixedClock{now}, IDGenerator: sequentialIDs()})
	cmd := IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: "jo-1", AncillaryOrderItemID: "anc-1", AccountID: "acct-1", TravelerRef: "trav-1", SegmentRefs: []string{"seg-1"}, PaymentIntentID: "pi-1", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour)}
	first, err := svc.IssuePolicy(context.Background(), cmd)
	if err != nil {
		t.Fatal(err)
	}
	second, err := svc.IssuePolicy(context.Background(), cmd)
	if err != nil {
		t.Fatal(err)
	}
	if first.ID != second.ID {
		t.Fatalf("expected duplicate to return existing policy, got %s and %s", first.ID, second.ID)
	}
}

func TestFileAndSettleClaimPublishesEvents(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	pub := &capturePublisher{}
	svc := NewInsuranceService(ServiceConfig{Publisher: pub, Clock: fixedClock{now}, IDGenerator: sequentialIDs()})
	policy, err := svc.IssuePolicy(context.Background(), IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: "jo-1", AncillaryOrderItemID: "anc-1", AccountID: "acct-1", TravelerRef: "trav-1", SegmentRefs: []string{"seg-1"}, PaymentIntentID: "pi-1", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour)})
	if err != nil {
		t.Fatal(err)
	}
	amount, _ := domain.NewMoney("CNY", 1000)
	claim, err := svc.FileClaim(context.Background(), FileClaimCommand{PolicyID: policy.ID, ClaimType: string(domain.ClaimDelayAuto), TriggerFactKey: "fact-1", DelayFact: &domain.DelayFact{SourceEventType: "SegmentArrived", SourceEventID: "evt-1", DelayMinutes: 90}, ClaimedAmount: amount})
	if err != nil {
		t.Fatalf("FileClaim: %v", err)
	}
	if claim.Status != domain.ClaimApproved {
		t.Fatalf("claim status = %s", claim.Status)
	}
	_, err = svc.SettleClaim(context.Background(), SettleClaimCommand{ClaimID: claim.ID, PayoutTarget: string(domain.PayoutPaymentRefund)})
	if err != nil {
		t.Fatalf("SettleClaim: %v", err)
	}
	if len(pub.events) != 3 || pub.events[1].EventType != "ClaimFiled" || pub.events[2].EventType != "ClaimSettled" {
		t.Fatalf("events = %#v", pub.events)
	}
}

func sequentialIDs() func(string) string {
	counters := map[string]int{}
	return func(prefix string) string {
		counters[prefix]++
		return prefix + "-" + string(rune('0'+counters[prefix]))
	}
}
