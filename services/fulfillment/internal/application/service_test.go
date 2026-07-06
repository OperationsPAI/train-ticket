package application

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

type recordingPublisher struct{ envelopes []EventEnvelope }

func (p *recordingPublisher) Publish(_ context.Context, envelope EventEnvelope) error {
	p.envelopes = append(p.envelopes, envelope)
	return nil
}

type failingOncePublisher struct {
	err       error
	attempts  int
	envelopes []EventEnvelope
}

func (p *failingOncePublisher) Publish(_ context.Context, envelope EventEnvelope) error {
	p.attempts++
	if p.attempts == 1 {
		return p.err
	}
	p.envelopes = append(p.envelopes, envelope)
	return nil
}

func seedTicket(t *testing.T, service *Service, entitlementID, segmentBookingID, journeyOrderID, travelerID, segmentRef string) {
	t.Helper()
	envelope := EventEnvelope{EventID: "evt-seed-" + entitlementID, EventType: "EntitlementIssued", Producer: "entitlement-ticketing", SchemaVersion: 1, Payload: json.RawMessage(`{"entitlementId":"` + entitlementID + `","segmentBookingId":"` + segmentBookingID + `","journeyOrderId":"` + journeyOrderID + `","travelerRef":"` + travelerID + `","segmentRef":"` + segmentRef + `"}`)}
	if err := service.HandleSubscribedEvent(context.Background(), envelope); err != nil {
		t.Fatalf("seed ticket: %v", err)
	}
}

func TestPublisherWrapsDomainEventInCanonicalEnvelope(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(NewInMemoryRepository(), publisher, NewInMemoryConsumedEventLog(), func(prefix string) string {
		return prefix + "-00000000-0000-4000-8000-000000000001"
	}, func() time.Time { return time.Date(2026, 7, 5, 10, 1, 0, 0, time.UTC) })

	seedTicket(t, service, "ent-abc123", "sb-def456", "ord-ghi789", "tvl-jkl012", "seg-mno345")
	occurredAt := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	_, err := service.VerifyBoarding(context.Background(), VerifyBoardingCommand{
		EntitlementID:    "ent-abc123",
		SegmentBookingID: "sb-def456",
		JourneyOrderID:   "ord-ghi789",
		TravelerID:       "tvl-jkl012",
		SegmentRef:       "seg-mno345",
		Source:           domain.FulfillmentSourceGate,
		SourceEventID:    "gate-scan-1",
		OccurredAt:       occurredAt,
	}, CommandMetadata{CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c0aa", CausationID: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c0bb"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(publisher.envelopes) != 1 {
		t.Fatalf("expected one published envelope, got %d", len(publisher.envelopes))
	}
	envelope := publisher.envelopes[0]
	if !strings.HasPrefix(envelope.EventID, "evt-") {
		t.Fatalf("eventId must be prefixed: %s", envelope.EventID)
	}
	if envelope.EventType != "BoardingVerified" || envelope.Producer != ProducerName || envelope.SchemaVersion != 1 {
		t.Fatalf("unexpected envelope: %#v", envelope)
	}
	if !strings.HasPrefix(envelope.CorrelationID, "corr-") || !strings.HasPrefix(envelope.CausationID, "cmd-") {
		t.Fatalf("unexpected correlation/causation: %#v", envelope)
	}
	if !envelope.OccurredAt.Equal(occurredAt) {
		t.Fatalf("unexpected occurredAt: %s", envelope.OccurredAt)
	}
	var payload struct {
		FulfillmentRecordID string `json:"fulfillmentRecordId"`
		EntitlementID       string `json:"entitlementId"`
		SourceEventID       string `json:"sourceEventId"`
	}
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		t.Fatalf("payload is not json: %v", err)
	}
	if payload.FulfillmentRecordID == "" || payload.EntitlementID != "ent-abc123" || payload.SourceEventID != "gate-scan-1" {
		t.Fatalf("unexpected payload: %#v", payload)
	}
}

func TestSubscribedEventHandlerDeduplicatesByEventID(t *testing.T) {
	log := NewInMemoryConsumedEventLog()
	service := NewService(NewInMemoryRepository(), NoopPublisher{}, log, nil, nil)
	envelope := EventEnvelope{EventID: "evt-duplicate", EventType: "EntitlementIssued", Producer: "entitlement-ticketing", SchemaVersion: 1}
	if err := service.HandleSubscribedEvent(context.Background(), envelope); err != nil {
		t.Fatalf("first event failed: %v", err)
	}
	if err := service.HandleSubscribedEvent(context.Background(), envelope); err != nil {
		t.Fatalf("duplicate event should be ack-safe no-op: %v", err)
	}
	seen, err := log.AlreadyConsumed(context.Background(), envelope.EventID)
	if err != nil || !seen {
		t.Fatalf("event was not recorded as consumed: seen=%v err=%v", seen, err)
	}
}

func TestPublishFailureRetainsPendingEventsForRetry(t *testing.T) {
	repo := NewInMemoryRepository()
	publisher := &failingOncePublisher{err: errors.New("redis unavailable")}
	service := NewService(repo, publisher, NewInMemoryConsumedEventLog(), func(prefix string) string {
		return prefix + "-00000000-0000-7000-8000-000000000001"
	}, func() time.Time { return time.Date(2026, 7, 5, 10, 1, 0, 0, time.UTC) })
	seedTicket(t, service, "ent-retry1", "sb-retry1", "ord-retry1", "tvl-retry1", "seg-retry1")
	cmd := VerifyBoardingCommand{
		EntitlementID:    "ent-retry1",
		SegmentBookingID: "sb-retry1",
		JourneyOrderID:   "ord-retry1",
		TravelerID:       "tvl-retry1",
		SegmentRef:       "seg-retry1",
		Source:           domain.FulfillmentSourceGate,
		SourceEventID:    "gate-scan-retry",
		OccurredAt:       time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC),
	}
	_, err := service.VerifyBoarding(context.Background(), cmd, CommandMetadata{})
	if !errors.Is(err, ErrPublishFailed) {
		t.Fatalf("expected publish failure, got %v", err)
	}
	record, err := repo.FindByEntitlementSegment(context.Background(), cmd.EntitlementID, cmd.SegmentBookingID, cmd.SegmentRef)
	if err != nil {
		t.Fatalf("mutated record was not saved: %v", err)
	}
	if record.Status != domain.FulfillmentStatusBoarded {
		t.Fatalf("record mutation was not retained: %s", record.Status)
	}
	if got := len(record.PendingEvents()); got != 1 {
		t.Fatalf("expected pending event after failed publish, got %d", got)
	}

	_, err = service.VerifyBoarding(context.Background(), cmd, CommandMetadata{})
	if err != nil {
		t.Fatalf("retry should publish retained event: %v", err)
	}
	if len(publisher.envelopes) != 1 {
		t.Fatalf("expected retained event to be published once, got %d", len(publisher.envelopes))
	}
	record, err = repo.FindByEntitlementSegment(context.Background(), cmd.EntitlementID, cmd.SegmentBookingID, cmd.SegmentRef)
	if err != nil {
		t.Fatal(err)
	}
	if got := len(record.PendingEvents()); got != 0 {
		t.Fatalf("expected pending events cleared after successful retry, got %d", got)
	}
}

func TestVerifyBoardingRequiresPreparedTicketAndRejectsVoided(t *testing.T) {
	service := NewService(NewInMemoryRepository(), &recordingPublisher{}, NewInMemoryConsumedEventLog(), nil, nil)
	cmd := VerifyBoardingCommand{EntitlementID: "ent-missing1", SegmentBookingID: "sb-missing1", JourneyOrderID: "ord-missing1", TravelerID: "tvl-missing1", SegmentRef: "seg-missing1", Source: domain.FulfillmentSourceGate, SourceEventID: "scan-missing", OccurredAt: time.Now().UTC()}
	if _, err := service.VerifyBoarding(context.Background(), cmd, CommandMetadata{}); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expected not found for unprepared ticket, got %v", err)
	}
	seedTicket(t, service, "ent-void1", "sb-void1", "ord-void1", "tvl-void1", "seg-void1")
	voided := EventEnvelope{EventID: "evt-void", EventType: "EntitlementVoided", Producer: "entitlement-ticketing", SchemaVersion: 1, Payload: json.RawMessage(`{"entitlementId":"ent-void1","segmentBookingId":"sb-void1"}`)}
	if err := service.HandleSubscribedEvent(context.Background(), voided); err != nil {
		t.Fatal(err)
	}
	cmd = VerifyBoardingCommand{EntitlementID: "ent-void1", SegmentBookingID: "sb-void1", JourneyOrderID: "ord-void1", TravelerID: "tvl-void1", SegmentRef: "seg-void1", Source: domain.FulfillmentSourceGate, SourceEventID: "scan-void", OccurredAt: time.Now().UTC()}
	if _, err := service.VerifyBoarding(context.Background(), cmd, CommandMetadata{}); !errors.Is(err, ErrDomainRuleViolation) {
		t.Fatalf("expected domain violation for voided ticket, got %v", err)
	}
}

func TestFulfillmentCompletedPublishesContractEvent(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(NewInMemoryRepository(), publisher, NewInMemoryConsumedEventLog(), nil, func() time.Time { return time.Date(2026, 7, 5, 10, 1, 0, 0, time.UTC) })
	seedTicket(t, service, "ent-complete1", "sb-complete1", "ord-complete1", "tvl-complete1", "seg-complete1")
	_, err := service.VerifyBoarding(context.Background(), VerifyBoardingCommand{EntitlementID: "ent-complete1", SegmentBookingID: "sb-complete1", JourneyOrderID: "ord-complete1", TravelerID: "tvl-complete1", SegmentRef: "seg-complete1", Source: domain.FulfillmentSourceGate, SourceEventID: "scan-complete", OccurredAt: time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)}, CommandMetadata{CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c0aa"})
	if err != nil {
		t.Fatalf("boarding: %v", err)
	}
	publisher.envelopes = nil
	completedAt := time.Date(2026, 7, 5, 11, 0, 0, 0, time.UTC)
	_, err = service.RecordFulfillmentCompleted(context.Background(), FulfillmentCompletedCommand{EntitlementID: "ent-complete1", SegmentBookingID: "sb-complete1", JourneyOrderID: "ord-complete1", TravelerID: "tvl-complete1", SegmentRef: "seg-complete1", CompletionSource: domain.CompletionSourceArrival, CompletedAt: completedAt}, CommandMetadata{CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c0aa"})
	if err != nil {
		t.Fatalf("complete: %v", err)
	}
	if len(publisher.envelopes) != 1 || publisher.envelopes[0].EventType != "FulfillmentCompleted" {
		t.Fatalf("unexpected envelopes: %#v", publisher.envelopes)
	}
	var payload map[string]any
	if err := json.Unmarshal(publisher.envelopes[0].Payload, &payload); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"fulfillmentRecordId", "entitlementId", "segmentBookingId", "journeyOrderId", "travelerId", "completedAt", "completionSource"} {
		if _, ok := payload[key]; !ok {
			t.Fatalf("missing %s in %#v", key, payload)
		}
	}
	if _, ok := payload["segmentRef"]; ok {
		t.Fatalf("FulfillmentCompleted payload must match contract exactly, got segmentRef")
	}
}
