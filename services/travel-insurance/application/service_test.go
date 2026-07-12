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
	if len(pub.events) != 1 || pub.events[0].EventType != "InsurancePolicyIssued" {
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

func TestDuplicatePolicyUniquenessIncludesProductCode(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	svc := NewInsuranceService(ServiceConfig{Clock: fixedClock{now}, IDGenerator: sequentialIDs()})
	base := IssuePolicyCommand{ProductVersion: "v1", JourneyOrderID: "jo-multi", AncillaryOrderItemID: "anc-multi", AccountID: "acct-multi", TravelerRef: "trav-multi", SegmentRefs: []string{"seg-multi"}, PaymentIntentID: "pi-multi", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour)}

	base.ProductCode = string(domain.ProductDelayInsurance)
	delay, err := svc.IssuePolicy(context.Background(), base)
	if err != nil {
		t.Fatal(err)
	}
	base.ProductCode = string(domain.ProductBaggageInsurance)
	baggage, err := svc.IssuePolicy(context.Background(), base)
	if err != nil {
		t.Fatal(err)
	}
	if delay.ID == baggage.ID {
		t.Fatalf("different products should issue distinct policies, got %s", delay.ID)
	}
	if baggage.ProductCode != domain.ProductBaggageInsurance {
		t.Fatalf("productCode = %s, want BAGGAGE_INSURANCE", baggage.ProductCode)
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
	if len(pub.events) != 5 || pub.events[1].EventType != "InsuranceClaimCreated" || pub.events[4].EventType != "InsurancePayoutCompleted" {
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

func TestDelayInsuranceAutoPayoutAndRefundCancellation(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	pub := &capturePublisher{}
	svc := NewInsuranceService(ServiceConfig{Publisher: pub, Clock: fixedClock{now}, IDGenerator: sequentialIDs()})
	policy, err := svc.IssuePolicy(context.Background(), IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: "jo-delay", AncillaryOrderItemID: "anc-delay", AccountID: "acct-delay", TravelerRef: "trav-delay", SegmentRefs: []string{"seg-delay"}, PaymentIntentID: "pi-delay", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour)})
	if err != nil {
		t.Fatal(err)
	}
	if policy.Premium.MinorUnits != 300 || policy.Status != domain.PolicyIssued {
		t.Fatalf("unexpected issued policy: %#v", policy)
	}
	advices, err := svc.ProcessTrainDelayed(context.Background(), TrainDelayedCommand{SegmentRef: "seg-delay", ServiceDate: "2026-07-10", DelayMinutes: 90, OccurredAt: now, SourceEventID: "evt-019f5414-026a-7000-8888-000000000001"})
	if err != nil {
		t.Fatal(err)
	}
	if len(advices) != 1 || advices[0].Amount.MinorUnits != 3000 {
		t.Fatalf("unexpected auto payout: %#v", advices)
	}
	paidPolicy, err := svc.GetPolicy(context.Background(), policy.ID)
	if err != nil {
		t.Fatal(err)
	}
	if paidPolicy.Status != domain.PolicyPaidOut {
		t.Fatalf("status = %s, want PAID_OUT", paidPolicy.Status)
	}

	refundable, err := svc.IssuePolicy(context.Background(), IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: "jo-refund", AncillaryOrderItemID: "anc-refund", AccountID: "acct-refund", TravelerRef: "trav-refund", SegmentRefs: []string{"seg-refund"}, PaymentIntentID: "pi-refund", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour)})
	if err != nil {
		t.Fatal(err)
	}
	cancelled, err := svc.CancelPolicyForRefund(context.Background(), RefundAppliedCommand{PolicyID: refundable.ID, RefundID: "refund-1"})
	if err != nil {
		t.Fatal(err)
	}
	if cancelled.Status != domain.PolicyCancelled || cancelled.RefundedPremium.MinorUnits != 300 {
		t.Fatalf("unexpected cancelled policy: %#v", cancelled)
	}
}

func TestManualClaimPendingThenApproved(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	svc := NewInsuranceService(ServiceConfig{Clock: fixedClock{now}, IDGenerator: sequentialIDs()})
	policy, err := svc.IssuePolicy(context.Background(), IssuePolicyCommand{ProductCode: string(domain.ProductBaggageInsurance), ProductVersion: "v1", JourneyOrderID: "jo-bag", AncillaryOrderItemID: "anc-bag", AccountID: "acct-bag", TravelerRef: "trav-bag", SegmentRefs: []string{"seg-bag"}, PaymentIntentID: "pi-bag", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour)})
	if err != nil {
		t.Fatal(err)
	}
	amount, _ := domain.NewMoney("CNY", 50000)
	claim, err := svc.FileClaim(context.Background(), FileClaimCommand{PolicyID: policy.ID, ClaimType: string(domain.ClaimBaggage), TriggerFactKey: "bag-1", Description: "lost baggage", EvidenceRefs: []string{"doc-1"}, ClaimedAmount: amount})
	if err != nil {
		t.Fatal(err)
	}
	if claim.Status != domain.ClaimPending {
		t.Fatalf("status = %s, want PENDING", claim.Status)
	}
	advice, err := svc.ApproveClaim(context.Background(), SettleClaimCommand{ClaimID: claim.ID})
	if err != nil {
		t.Fatal(err)
	}
	if advice.Amount.MinorUnits != amount.MinorUnits {
		t.Fatalf("advice amount = %#v", advice.Amount)
	}
}
