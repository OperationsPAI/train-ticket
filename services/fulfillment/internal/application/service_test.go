package application

import (
	"context"
	"encoding/json"
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

func TestPublisherWrapsDomainEventInCanonicalEnvelope(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(NewInMemoryRepository(), publisher, NewInMemoryConsumedEventLog(), func(prefix string) string {
		return prefix + "-00000000-0000-4000-8000-000000000001"
	}, func() time.Time { return time.Date(2026, 7, 5, 10, 1, 0, 0, time.UTC) })

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
	}, CommandMetadata{CorrelationID: "corr-00000000-0000-4000-8000-00000000c0aa", CausationID: "cmd-00000000-0000-4000-8000-00000000c0bb"})
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
