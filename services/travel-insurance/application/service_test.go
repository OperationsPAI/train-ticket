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

type payoutExecutorStub struct {
	receipt PayoutReceipt
	err     error
	calls   int
	advices []domain.PayoutAdvice
}

type journeyCoverageResolverStub struct {
	coverage JourneyCoverage
	err      error
	calls    int
}

func (s *journeyCoverageResolverStub) ResolveJourneyCoverage(context.Context, string, string) (JourneyCoverage, error) {
	s.calls++
	return s.coverage, s.err
}

func (s *payoutExecutorStub) Disburse(_ context.Context, _ domain.Policy, _ domain.Claim, advice domain.PayoutAdvice, _ string) (PayoutReceipt, error) {
	s.calls++
	s.advices = append(s.advices, advice)
	return s.receipt, s.err
}

func TestIssuePolicyPublishesPolicyIssued(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	pub := &capturePublisher{}
	executor := &payoutExecutorStub{receipt: PayoutReceipt{ExecutionRef: "ben-1", AccountID: "acct-1", Amount: domain.Money{Currency: "CNY", MinorUnits: 1000}, Target: domain.PayoutWalletBenefit}}
	svc := NewInsuranceService(ServiceConfig{Publisher: pub, Clock: fixedClock{now}, IDGenerator: sequentialIDs(), PayoutExecutor: executor})
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
	executor := &payoutExecutorStub{receipt: PayoutReceipt{ExecutionRef: "ben-1", AccountID: "acct-1", Amount: domain.Money{Currency: "CNY", MinorUnits: 1000}, Target: domain.PayoutWalletBenefit}}
	svc := NewInsuranceService(ServiceConfig{Publisher: pub, Clock: fixedClock{now}, IDGenerator: sequentialIDs(), PayoutExecutor: executor})
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
	_, err = svc.SettleClaim(context.Background(), SettleClaimCommand{ClaimID: claim.ID, PayoutTarget: string(domain.PayoutWalletBenefit)})
	if err != nil {
		t.Fatalf("SettleClaim: %v", err)
	}
	if len(pub.events) != 4 || pub.events[1].EventType != "InsuranceClaimCreated" || pub.events[3].EventType != "InsurancePayoutCompleted" || executor.calls != 1 {
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
	executor := &payoutExecutorStub{}
	svc := NewInsuranceService(ServiceConfig{Publisher: pub, Clock: fixedClock{now}, IDGenerator: sequentialIDs(), PayoutExecutor: executor})
	policy, err := svc.IssuePolicy(context.Background(), IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: "jo-delay", AncillaryOrderItemID: "anc-delay", AccountID: "acct-delay", TravelerRef: "trav-delay", SegmentRefs: []string{"seg-delay"}, PaymentIntentID: "pi-delay", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour)})
	if err != nil {
		t.Fatal(err)
	}
	if policy.Premium.MinorUnits != 300 || policy.Status != domain.PolicyIssued {
		t.Fatalf("unexpected issued policy: %#v", policy)
	}
	executor.receipt = PayoutReceipt{ExecutionRef: "ben-delay", AccountID: policy.AccountID, Amount: domain.Money{Currency: "CNY", MinorUnits: 3000}, Target: domain.PayoutWalletBenefit}
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
	executor := &payoutExecutorStub{receipt: PayoutReceipt{ExecutionRef: "ben-bag", AccountID: "acct-bag", Amount: domain.Money{Currency: "CNY", MinorUnits: 50000}, Target: domain.PayoutWalletBenefit}}
	svc := NewInsuranceService(ServiceConfig{Clock: fixedClock{now}, IDGenerator: sequentialIDs(), PayoutExecutor: executor})
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

func TestPayoutFailureLeavesClaimApprovedAndReusesDecision(t *testing.T) {
	now := time.Date(2026, 9, 25, 4, 0, 0, 0, time.UTC)
	executor := &payoutExecutorStub{err: ErrPayoutUnavailable}
	svc := NewInsuranceService(ServiceConfig{Clock: fixedClock{now}, IDGenerator: sequentialIDs(), PayoutExecutor: executor})
	policy, err := svc.IssuePolicy(context.Background(), IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: "jo-retry", AncillaryOrderItemID: "anc-retry", AccountID: "acct-retry", TravelerRef: "trav-retry", SegmentRefs: []string{"seg-retry"}, PaymentIntentID: "pi-retry", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour)})
	if err != nil {
		t.Fatal(err)
	}
	amount, _ := domain.NewMoney("CNY", 1000)
	claim, err := svc.FileClaim(context.Background(), FileClaimCommand{PolicyID: policy.ID, ClaimType: string(domain.ClaimDelayAuto), TriggerFactKey: "fact-retry", DelayFact: &domain.DelayFact{SourceEventType: "SegmentArrived", SourceEventID: "evt-retry", DelayMinutes: 90}, ClaimedAmount: amount})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := svc.ApproveClaim(context.Background(), SettleClaimCommand{ClaimID: claim.ID}); err == nil {
		t.Fatal("expected payout failure")
	}
	after, err := svc.GetClaim(context.Background(), claim.ID)
	if err != nil {
		t.Fatal(err)
	}
	if after.Status != domain.ClaimApproved {
		t.Fatalf("claim status=%s", after.Status)
	}
	firstKey := executor.advices[0].IdempotencyKey
	executor.err = nil
	executor.receipt = PayoutReceipt{ExecutionRef: "ben-retry", AccountID: policy.AccountID, Amount: amount, Target: domain.PayoutWalletBenefit}
	if _, err := svc.ApproveClaim(context.Background(), SettleClaimCommand{ClaimID: claim.ID}); err != nil {
		t.Fatal(err)
	}
	if executor.calls != 2 || executor.advices[1].IdempotencyKey != firstKey || executor.advices[1].ID != executor.advices[0].ID {
		t.Fatalf("payout retry changed identity: %#v", executor.advices)
	}
}

func TestPayoutReceiptMustMatchApprovedClaim(t *testing.T) {
	now := time.Date(2026, 9, 25, 4, 0, 0, 0, time.UTC)
	executor := &payoutExecutorStub{receipt: PayoutReceipt{ExecutionRef: "ben-wrong", AccountID: "acct-other", Amount: domain.Money{Currency: "CNY", MinorUnits: 1000}, Target: domain.PayoutWalletBenefit}}
	svc := NewInsuranceService(ServiceConfig{Clock: fixedClock{now}, IDGenerator: sequentialIDs(), PayoutExecutor: executor})
	policy, err := svc.IssuePolicy(context.Background(), IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: "jo-mismatch", AncillaryOrderItemID: "anc-mismatch", AccountID: "acct-match", TravelerRef: "trav-match", SegmentRefs: []string{"seg-match"}, PaymentIntentID: "pi-match", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour)})
	if err != nil {
		t.Fatal(err)
	}
	amount, _ := domain.NewMoney("CNY", 1000)
	claim, err := svc.FileClaim(context.Background(), FileClaimCommand{PolicyID: policy.ID, ClaimType: string(domain.ClaimDelayAuto), TriggerFactKey: "fact-match", DelayFact: &domain.DelayFact{SourceEventType: "SegmentArrived", SourceEventID: "evt-match", DelayMinutes: 90}, ClaimedAmount: amount})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := svc.ApproveClaim(context.Background(), SettleClaimCommand{ClaimID: claim.ID}); err == nil {
		t.Fatal("expected mismatched payout receipt rejection")
	}
	after, _ := svc.GetClaim(context.Background(), claim.ID)
	if after.Status != domain.ClaimApproved {
		t.Fatalf("claim status=%s", after.Status)
	}
}

func TestChangedJourneyUpdatesOnlyAffectedTravelerPolicyAndIsIdempotent(t *testing.T) {
	now := time.Date(2026, 9, 25, 4, 0, 0, 0, time.UTC)
	newStart := now.Add(72 * time.Hour)
	newEnd := newStart.Add(3 * time.Hour)
	resolver := &journeyCoverageResolverStub{coverage: JourneyCoverage{
		OrderID: "jo-change", AccountID: "acct-change", Status: "CONFIRMED",
		TravelerRefs: []string{"trav-a"}, ReplacedSegmentRefs: []string{"seg-old"},
		SegmentRefs: []string{"seg-new"}, StartsAt: newStart, EndsAt: newEnd,
	}}
	pub := &capturePublisher{}
	svc := NewInsuranceService(ServiceConfig{Publisher: pub, Clock: fixedClock{now}, IDGenerator: sequentialIDs(), JourneyCoverageResolver: resolver})
	first, err := svc.IssuePolicy(context.Background(), IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: "jo-change", AncillaryOrderItemID: "anc-a", AccountID: "acct-change", TravelerRef: "trav-a", SegmentRefs: []string{"seg-old"}, PaymentIntentID: "pi-a", CoverageStartAt: now.Add(time.Hour), CoverageEndAt: now.Add(2 * time.Hour)})
	if err != nil {
		t.Fatal(err)
	}
	second, err := svc.IssuePolicy(context.Background(), IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: "jo-change", AncillaryOrderItemID: "anc-b", AccountID: "acct-change", TravelerRef: "trav-b", SegmentRefs: []string{"seg-old"}, PaymentIntentID: "pi-b", CoverageStartAt: now.Add(time.Hour), CoverageEndAt: now.Add(2 * time.Hour)})
	if err != nil {
		t.Fatal(err)
	}
	beforeEvents := len(pub.events)
	command := JourneyAdjustedCommand{JourneyOrderID: "jo-change", AccountID: "acct-change", CaseID: "psc-change", SegmentRefs: []string{"seg-new", "seg-unaffected"}}
	updated, err := svc.AdjustPoliciesForChangedJourney(context.Background(), command)
	if err != nil {
		t.Fatal(err)
	}
	if len(updated) != 1 || updated[0].ID != first.ID || !updated[0].CoverageStartAt.Equal(newStart) || !updated[0].CoverageEndAt.Equal(newEnd) || len(updated[0].SegmentRefs) != 1 || updated[0].SegmentRefs[0] != "seg-new" {
		t.Fatalf("unexpected adjusted policies: %#v", updated)
	}
	unaffected, err := svc.GetPolicy(context.Background(), second.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(unaffected.SegmentRefs) != 1 || unaffected.SegmentRefs[0] != "seg-old" {
		t.Fatalf("unaffected traveler changed: %#v", unaffected)
	}
	if len(pub.events) != beforeEvents+1 || pub.events[len(pub.events)-1].EventType != "InsurancePolicyCoverageAdjusted" {
		t.Fatalf("unexpected events: %#v", pub.events)
	}
	version := updated[0].AggregateVersion
	if _, err := svc.AdjustPoliciesForChangedJourney(context.Background(), command); err != nil {
		t.Fatal(err)
	}
	replayed, _ := svc.GetPolicy(context.Background(), first.ID)
	if replayed.AggregateVersion != version || len(pub.events) != beforeEvents+1 {
		t.Fatal("duplicate change must not mutate or publish")
	}
}
