package application

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"
)

type recordingPublisher struct {
	envelopes []EventEnvelope
	failures  int
}

func (p *recordingPublisher) Publish(_ context.Context, envelope EventEnvelope) error {
	if p.failures > 0 {
		p.failures--
		return errors.New("publish unavailable")
	}
	p.envelopes = append(p.envelopes, envelope)
	return nil
}

func TestPublisherReceivesCorrectServicePlanEnvelope(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(publisher)
	_, _, err := service.CreateScheduledService(context.Background(), CreateScheduledServiceCommand{
		CarrierID:         "car-0194f2e0-7b3e-7610-0284-5c26e8b0c001",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
		IdempotencyKey:    "0194f2e0-7b3e-7610-0284-5c26e8b0c101",
		CorrelationID:     "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444",
		CausationID:       "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555",
		RequestHash:       "hash",
	})
	if err != nil {
		t.Fatalf("create scheduled service: %v", err)
	}
	if len(publisher.envelopes) != 1 {
		t.Fatalf("expected one envelope, got %d", len(publisher.envelopes))
	}
	envelope := publisher.envelopes[0]
	if envelope.Producer != ProducerServicePlan || envelope.EventType != "ServicePlanPublished" || envelope.SchemaVersion != 1 {
		t.Fatalf("unexpected envelope headers: %#v", envelope)
	}
	assertPrefixedUUID(t, envelope.EventID, "evt")
	assertPrefixedUUID(t, envelope.CorrelationID, "corr")
	assertPrefixedUUID(t, envelope.CausationID, "cmd")
	if envelope.OccurredAt.IsZero() {
		t.Fatalf("missing occurredAt: %#v", envelope)
	}
	var payload map[string]any
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		t.Fatalf("payload is not json: %v", err)
	}
	if payload["serviceNumber"] != "G1234" || payload["status"] != "ACTIVE" {
		t.Fatalf("unexpected payload: %#v", payload)
	}
}

func TestDeduplicatingHandlerSkipsDuplicateEventID(t *testing.T) {
	store := NewInMemoryDedupStore()
	calls := 0
	handler := DeduplicatingHandler(store, func(context.Context, EventEnvelope) error {
		calls++
		return nil
	})
	envelope := EventEnvelope{EventID: "evt-0194f2e0-7b3e-7610-0284-5c26e8b0c301", EventType: "Any", OccurredAt: time.Now().UTC(), CorrelationID: "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c401", CausationID: "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c501", Producer: ProducerServicePlan, SchemaVersion: 1, Payload: json.RawMessage(`{}`)}
	if err := handler(context.Background(), envelope); err != nil {
		t.Fatalf("first handle: %v", err)
	}
	if err := handler(context.Background(), envelope); err != nil {
		t.Fatalf("duplicate handle: %v", err)
	}
	if calls != 1 {
		t.Fatalf("expected one handler call, got %d", calls)
	}
}

func TestEnvelopeIDShapesAndCausationIndependentOfIdempotencyKey(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(publisher)
	_, _, err := service.CreateScheduledService(context.Background(), CreateScheduledServiceCommand{
		CarrierID:         "car-0194f2e0-7b3e-7610-0284-5c26e8b0c001",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
		IdempotencyKey:    "0194f2e0-7b3e-7610-0284-5c26e8b0c102",
		CorrelationID:     "not-canonical",
		RequestHash:       "hash",
	})
	if err != nil {
		t.Fatalf("create scheduled service: %v", err)
	}
	if len(publisher.envelopes) != 1 {
		t.Fatalf("expected one envelope, got %d", len(publisher.envelopes))
	}
	envelope := publisher.envelopes[0]
	assertPrefixedUUID(t, envelope.EventID, "evt")
	assertPrefixedUUID(t, envelope.CorrelationID, "corr")
	if envelope.CausationID != "" {
		assertPrefixedUUID(t, envelope.CausationID, "cmd")
	}
	if envelope.CausationID == "0194f2e0-7b3e-7610-0284-5c26e8b0c102" || envelope.CausationID == "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c102" {
		t.Fatalf("causationId must not be derived from idempotency key")
	}
}

func TestDomainRuleViolationDoesNotPublish(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(publisher)
	_, _, err := service.CreateScheduledService(context.Background(), CreateScheduledServiceCommand{
		ServiceRef:        "ss-0194f2e0-7b3e-7610-0284-5c26e8b0c201",
		CarrierID:         "car-0194f2e0-7b3e-7610-0284-5c26e8b0c001",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
		IdempotencyKey:    "0194f2e0-7b3e-7610-0284-5c26e8b0c103",
		RequestHash:       "hash-service",
	})
	if err != nil {
		t.Fatalf("create scheduled service: %v", err)
	}
	publishedBeforeRejectedChange := len(publisher.envelopes)
	_, _, err = service.CreateServiceSegment(context.Background(), CreateServiceSegmentCommand{
		ScheduledServiceRef: "ss-0194f2e0-7b3e-7610-0284-5c26e8b0c201",
		OriginStopRef:       "node-a",
		DestinationStopRef:  "node-a",
		DepartureTime:       time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:         time.Date(2026, 7, 5, 11, 30, 0, 0, time.UTC),
		IdempotencyKey:      "0194f2e0-7b3e-7610-0284-5c26e8b0c104",
		RequestHash:         "hash-segment",
	})
	if err == nil {
		t.Fatal("expected domain rule violation")
	}
	if len(publisher.envelopes) != publishedBeforeRejectedChange {
		t.Fatalf("expected no events for rejected state change, got %d new events", len(publisher.envelopes)-publishedBeforeRejectedChange)
	}
}

func assertPrefixedUUID(t *testing.T, value, prefix string) {
	t.Helper()
	if !validPrefixedUUIDv7(value, prefix) {
		t.Fatalf("%s is not a canonical %s-prefixed UUID", value, prefix)
	}
}

func TestInvalidIdempotencyKeyRejected(t *testing.T) {
	service := NewService(&recordingPublisher{})
	_, _, err := service.CreateScheduledService(context.Background(), CreateScheduledServiceCommand{
		CarrierID:         "car-0194f2e0-7b3e-7610-0284-5c26e8b0c001",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
		IdempotencyKey:    "idem-invalid",
		RequestHash:       "hash",
	})
	if !errors.Is(err, ErrValidation) {
		t.Fatalf("expected validation error for invalid idempotency key, got %v", err)
	}
}

func TestInvalidCarrierIDRejectedBeforePublish(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(publisher)
	_, _, err := service.CreateScheduledService(context.Background(), CreateScheduledServiceCommand{
		CarrierID:         "car-invalid",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
		IdempotencyKey:    "0194f2e0-7b3e-7610-0284-5c26e8b0c105",
		RequestHash:       "hash",
	})
	if !errors.Is(err, ErrValidation) {
		t.Fatalf("expected validation error for invalid carrierId, got %v", err)
	}
	if len(publisher.envelopes) != 0 {
		t.Fatalf("expected no events for invalid carrierId, got %d", len(publisher.envelopes))
	}
}

func TestPendingEventRetainedAndReplayedAfterPublishFailure(t *testing.T) {
	publisher := &recordingPublisher{failures: 1}
	service := NewService(publisher)
	command := CreateScheduledServiceCommand{
		CarrierID:         "car-0194f2e0-7b3e-7610-0284-5c26e8b0c001",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
		IdempotencyKey:    "0194f2e0-7b3e-7610-0284-5c26e8b0c106",
		RequestHash:       "hash",
	}
	_, _, err := service.CreateScheduledService(context.Background(), command)
	if !errors.Is(err, ErrPublish) {
		t.Fatalf("expected publish error, got %v", err)
	}
	if len(publisher.envelopes) != 0 {
		t.Fatalf("failed publish should not be recorded as delivered")
	}
	_, replay, err := service.CreateScheduledService(context.Background(), command)
	if err != nil {
		t.Fatalf("expected replay to flush pending event: %v", err)
	}
	if replay == nil || replay.StatusCode != 201 {
		t.Fatalf("expected original response replay, got %#v", replay)
	}
	if len(publisher.envelopes) != 1 {
		t.Fatalf("expected pending event to publish once, got %d", len(publisher.envelopes))
	}
}
