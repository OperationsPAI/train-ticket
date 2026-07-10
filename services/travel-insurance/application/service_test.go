package application

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
	"github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

type recordingPublisher struct{ events []messaging.EventEnvelope }

func (p *recordingPublisher) Publish(_ context.Context, e messaging.EventEnvelope) error {
	p.events = append(p.events, e)
	return nil
}

func TestIssueFileClaimAndSettlePublishEvents(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	repo := NewInMemoryRepository()
	publisher := &recordingPublisher{}
	svc := NewInsuranceService(repo, publisher, nil, func() time.Time { return now })
	policy, err := svc.Issue(context.Background(), IssuePolicyCommand{ProductCode: domain.ProductDelayInsurance, JourneyOrderID: "ord-1", AccountID: "acct-1", TravelerRef: "tvl-1", SegmentRefs: []string{"seg-1"}, CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour)})
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	if policy.PolicyNumber == "" || policy.Status != domain.PolicyActive {
		t.Fatalf("unexpected policy: %#v", policy)
	}
	claim, err := svc.FileClaim(context.Background(), ClaimCommand{PolicyID: policy.ID, ClaimType: domain.ClaimDelayAuto, TriggerFactKey: "seg-1|delay", EvidenceRefs: []string{"evt-delay"}, ClaimedAmount: domain.Money{Currency: "CNY", MinorUnits: 1000}})
	if err != nil {
		t.Fatalf("claim: %v", err)
	}
	advice, err := svc.Settle(context.Background(), SettleClaimCommand{ClaimID: claim.ID, Amount: domain.Money{Currency: "CNY", MinorUnits: 1000}, PayoutTarget: domain.PayoutManualReview, ReasonCode: "DELAY"})
	if err != nil {
		t.Fatalf("settle: %v", err)
	}
	if advice.Status != "RECOMMENDED" {
		t.Fatalf("unexpected advice: %#v", advice)
	}
	if got := []string{publisher.events[0].EventType, publisher.events[1].EventType, publisher.events[2].EventType}; got[0] != domain.EventPolicyIssued || got[1] != domain.EventClaimFiled || got[2] != domain.EventClaimSettled {
		t.Fatalf("events mismatch: %#v", got)
	}
}

func TestJourneyOrderConfirmedCreatesOffer(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	repo := NewInMemoryRepository()
	svc := NewInsuranceService(repo, nil, nil, func() time.Time { return now })
	payload := JourneyOrderConfirmedPayload{OrderID: "ord-123", AccountID: "acct-1", ConfirmedAt: now}
	body, _ := json.Marshal(payload)
	envelope, err := messaging.NewEventEnvelope("JourneyOrderConfirmed", "journey-order", ids.NewCorrelationID(), json.RawMessage(body))
	if err != nil {
		t.Fatal(err)
	}
	if err := svc.HandleEvent(context.Background(), envelope); err != nil {
		t.Fatalf("handle: %v", err)
	}
	offers, err := repo.ListOffersByOrder(context.Background(), "ord-123")
	if err != nil {
		t.Fatal(err)
	}
	if len(offers) != 1 || offers[0].ProductCode != domain.ProductDelayInsurance {
		t.Fatalf("unexpected offers: %#v", offers)
	}
}
