package messaging

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/travel-insurance/application"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

type fixedClock struct{ t time.Time }

func (c fixedClock) Now() time.Time { return c.t }

type capturePublisher struct{ events []kitmsg.EventEnvelope }

func (p *capturePublisher) Publish(_ context.Context, e kitmsg.EventEnvelope) error {
	p.events = append(p.events, e)
	return nil
}

func sequentialIDs() func(string) string {
	counters := map[string]int{}
	return func(prefix string) string {
		counters[prefix]++
		return prefix + "-" + string(rune('0'+counters[prefix]))
	}
}

func newTestService(t *testing.T, now time.Time, pub *capturePublisher) *application.InsuranceService {
	t.Helper()
	return application.NewInsuranceService(application.ServiceConfig{
		Publisher:   pub,
		Clock:       fixedClock{now},
		IDGenerator: sequentialIDs(),
	})
}

func makeEnvelope(t *testing.T, eventType string, payload any, occurredAt time.Time) kitmsg.EventEnvelope {
	t.Helper()
	env, err := kitmsg.NewEventEnvelope(eventType, "journey-order", "", payload, kitmsg.EnvelopeOptions{Now: occurredAt})
	if err != nil {
		t.Fatalf("make envelope: %v", err)
	}
	return env
}

func TestHandleJourneyOrderCreated_IssuesPolicy(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	pub := &capturePublisher{}
	svc := newTestService(t, now, pub)
	handler := NewInboundHandler(svc)

	envelope := makeEnvelope(t, "JourneyOrderCreated", map[string]any{
		"orderId":      "ord-created",
		"accountId":    "acc-created",
		"segmentRefs":  []string{"seg-created"},
		"travelerRefs": []map[string]any{{"travelerId": "tvl-created", "travelerType": "ADULT"}},
		"createdAt":    now.Format(time.RFC3339),
	}, now)

	if err := handler.Handle(context.Background(), envelope); err != nil {
		t.Fatalf("Handle: %v", err)
	}
	if len(pub.events) != 1 || pub.events[0].EventType != "InsurancePolicyIssued" {
		t.Fatalf("expected policy issued from JourneyOrderCreated, got %#v", pub.events)
	}
}

func TestHandleTrainDelayed_TriggersAutoPayout(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	pub := &capturePublisher{}
	svc := newTestService(t, now, pub)
	handler := NewInboundHandler(svc)
	_, err := svc.IssuePolicy(context.Background(), application.IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: "ord-delay", AncillaryOrderItemID: "anc-delay", AccountID: "acc-delay", TravelerRef: "tvl-delay", SegmentRefs: []string{"seg-delay"}, PaymentIntentID: "pi-delay", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour)})
	if err != nil {
		t.Fatal(err)
	}

	envelope := makeEnvelope(t, "TrainDelayed", map[string]any{
		"serviceRef":            "svc-delay",
		"segmentRef":            "seg-delay",
		"delayMinutes":          90,
		"estimatedNewDeparture": now.Format(time.RFC3339),
	}, now)
	if err := handler.Handle(context.Background(), envelope); err != nil {
		t.Fatalf("Handle: %v", err)
	}

	policy, err := svc.GetPolicy(context.Background(), "pol-1")
	if err != nil {
		t.Fatal(err)
	}
	if policy.Status != domain.PolicyPaidOut {
		t.Fatalf("status = %s, want PAID_OUT", policy.Status)
	}
	if got := pub.events[len(pub.events)-1].EventType; got != "InsurancePayoutCompleted" {
		t.Fatalf("last event = %s, want InsurancePayoutCompleted", got)
	}
}

func TestHandlePostSalesApplied_CancelsRefundedPolicy(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	pub := &capturePublisher{}
	svc := newTestService(t, now, pub)
	handler := NewInboundHandler(svc)
	_, err := svc.IssuePolicy(context.Background(), application.IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: "ord-refund", AncillaryOrderItemID: "anc-refund", AccountID: "acc-refund", TravelerRef: "tvl-refund", SegmentRefs: []string{"seg-refund"}, PaymentIntentID: "pi-refund", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour)})
	if err != nil {
		t.Fatal(err)
	}

	envelope := makeEnvelope(t, "PostSalesApplied", map[string]any{
		"caseId":        "psc-refund",
		"orderId":       "ord-refund",
		"resultSummary": map[string]any{"description": "refund executed"},
	}, now)
	if err := handler.Handle(context.Background(), envelope); err != nil {
		t.Fatalf("Handle: %v", err)
	}

	policy, err := svc.GetPolicy(context.Background(), "pol-1")
	if err != nil {
		t.Fatal(err)
	}
	if policy.Status != domain.PolicyCancelled || policy.RefundedPremium.MinorUnits != 300 {
		t.Fatalf("unexpected policy after PostSalesApplied: %#v", policy)
	}
	if got := pub.events[len(pub.events)-1].EventType; got != "InsurancePolicyCancelled" {
		t.Fatalf("last event = %s, want InsurancePolicyCancelled", got)
	}
}

func TestHandleJourneyOrderConfirmed_NewPayloadFormat(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	pub := &capturePublisher{}
	svc := newTestService(t, now, pub)
	handler := NewInboundHandler(svc)

	envelope := makeEnvelope(t, "JourneyOrderConfirmed", map[string]any{
		"orderId":   "ord-001",
		"accountId": "acc-001",
		"monetarySummary": map[string]any{
			"total": map[string]any{"currency": "CNY", "minorUnits": 10000},
		},
		"segmentRefs":  []string{"seg-001"},
		"travelerRefs": []map[string]any{{"travelerId": "tvl-001", "travelerType": "ADULT"}},
	}, now)

	if err := handler.Handle(context.Background(), envelope); err != nil {
		t.Fatalf("Handle: %v", err)
	}
	if len(pub.events) != 1 {
		t.Fatalf("expected 1 published event, got %d", len(pub.events))
	}
	if pub.events[0].EventType != "InsurancePolicyIssued" {
		t.Fatalf("expected InsurancePolicyIssued event, got %s", pub.events[0].EventType)
	}

	var payload map[string]any
	if err := json.Unmarshal(pub.events[0].Payload, &payload); err != nil {
		t.Fatal(err)
	}
	if payload["journeyOrderId"] != "ord-001" {
		t.Fatalf("journeyOrderId = %v", payload["journeyOrderId"])
	}
	if payload["travelerRef"] != "tvl-001" {
		t.Fatalf("travelerRef = %v", payload["travelerRef"])
	}
	if payload["productCode"] != string(domain.ProductDelayInsurance) {
		t.Fatalf("productCode = %v", payload["productCode"])
	}
}

func TestHandleJourneyOrderConfirmed_LegacyPayloadFormat(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	pub := &capturePublisher{}
	svc := newTestService(t, now, pub)
	handler := NewInboundHandler(svc)

	envelope := makeEnvelope(t, "JourneyOrderConfirmed", map[string]any{
		"orderId":         "ord-002",
		"accountId":       "acc-002",
		"travelerRef":     "trav-002",
		"segmentRefs":     []string{"seg-002"},
		"paymentIntentId": "pi-002",
	}, now)

	if err := handler.Handle(context.Background(), envelope); err != nil {
		t.Fatalf("Handle: %v", err)
	}
	if len(pub.events) != 1 || pub.events[0].EventType != "InsurancePolicyIssued" {
		t.Fatalf("expected 1 InsurancePolicyIssued event, got %v", pub.events)
	}
}

func TestHandleJourneyOrderConfirmed_Idempotent(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	pub := &capturePublisher{}
	svc := newTestService(t, now, pub)
	handler := NewInboundHandler(svc)

	payload := map[string]any{
		"orderId":      "ord-003",
		"accountId":    "acc-003",
		"segmentRefs":  []string{"seg-003"},
		"travelerRefs": []map[string]any{{"travelerId": "tvl-003", "travelerType": "ADULT"}},
	}

	env1 := makeEnvelope(t, "JourneyOrderConfirmed", payload, now)
	if err := handler.Handle(context.Background(), env1); err != nil {
		t.Fatalf("first Handle: %v", err)
	}
	if len(pub.events) != 1 {
		t.Fatalf("expected 1 event after first handle, got %d", len(pub.events))
	}

	env2 := makeEnvelope(t, "JourneyOrderConfirmed", payload, now)
	if err := handler.Handle(context.Background(), env2); err != nil {
		t.Fatalf("second Handle: %v", err)
	}
	// Should still be 1 event - the duplicate was detected and returned the existing policy
	if len(pub.events) != 1 {
		t.Fatalf("expected 1 event after duplicate handle, got %d", len(pub.events))
	}
}

func TestHandleJourneyOrderConfirmed_SkipsMissingOrderID(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	pub := &capturePublisher{}
	svc := newTestService(t, now, pub)
	handler := NewInboundHandler(svc)

	envelope := makeEnvelope(t, "JourneyOrderConfirmed", map[string]any{
		"accountId":    "acc-004",
		"segmentRefs":  []string{"seg-004"},
		"travelerRefs": []map[string]any{{"travelerId": "tvl-004", "travelerType": "ADULT"}},
	}, now)

	if err := handler.Handle(context.Background(), envelope); err != nil {
		t.Fatalf("Handle: %v", err)
	}
	if len(pub.events) != 0 {
		t.Fatalf("expected no events for missing orderId, got %d", len(pub.events))
	}
}

func TestHandleJourneyOrderConfirmed_SkipsMissingTraveler(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	pub := &capturePublisher{}
	svc := newTestService(t, now, pub)
	handler := NewInboundHandler(svc)

	envelope := makeEnvelope(t, "JourneyOrderConfirmed", map[string]any{
		"orderId":     "ord-005",
		"accountId":   "acc-005",
		"segmentRefs": []string{"seg-005"},
	}, now)

	if err := handler.Handle(context.Background(), envelope); err != nil {
		t.Fatalf("Handle: %v", err)
	}
	if len(pub.events) != 0 {
		t.Fatalf("expected no events for missing traveler, got %d", len(pub.events))
	}
}

func TestHandleJourneyOrderConfirmed_SynthesizesPaymentIntent(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	pub := &capturePublisher{}
	svc := newTestService(t, now, pub)
	handler := NewInboundHandler(svc)

	envelope := makeEnvelope(t, "JourneyOrderConfirmed", map[string]any{
		"orderId":      "ord-006",
		"accountId":    "acc-006",
		"segmentRefs":  []string{"seg-006"},
		"travelerRefs": []map[string]any{{"travelerId": "tvl-006", "travelerType": "ADULT"}},
	}, now)

	if err := handler.Handle(context.Background(), envelope); err != nil {
		t.Fatalf("Handle: %v", err)
	}

	policy, err := svc.GetPolicy(context.Background(), "pol-1")
	if err != nil {
		t.Fatalf("GetPolicy: %v", err)
	}
	if policy.PaymentIntentID != "auto-pi:ord-006" {
		t.Fatalf("expected synthesized paymentIntentId, got %q", policy.PaymentIntentID)
	}
}

func TestHandleUnknownEventType_IsIgnored(t *testing.T) {
	now := time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC)
	pub := &capturePublisher{}
	svc := newTestService(t, now, pub)
	handler := NewInboundHandler(svc)

	envelope := makeEnvelope(t, "SomeUnknownEvent", map[string]any{"key": "value"}, now)
	if err := handler.Handle(context.Background(), envelope); err != nil {
		t.Fatalf("Handle: %v", err)
	}
	if len(pub.events) != 0 {
		t.Fatalf("expected no events for unknown event type, got %d", len(pub.events))
	}
}
