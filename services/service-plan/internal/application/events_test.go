package application

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

type recordingPublisher struct {
	envelopes []EventEnvelope
}

func (p *recordingPublisher) Publish(_ context.Context, envelope EventEnvelope) error {
	p.envelopes = append(p.envelopes, envelope)
	return nil
}

func TestPublisherReceivesCorrectServicePlanEnvelope(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(publisher)
	_, _, err := service.CreateScheduledService(context.Background(), CreateScheduledServiceCommand{
		CarrierID:         "car-1",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
		IdempotencyKey:    "idem-envelope",
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
	envelope := EventEnvelope{EventID: "evt-duplicate", EventType: "Any", OccurredAt: time.Now().UTC(), CorrelationID: "corr", CausationID: "cmd", Producer: ProducerServicePlan, SchemaVersion: 1, Payload: json.RawMessage(`{}`)}
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
		CarrierID:         "car-1",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
		IdempotencyKey:    "idem-not-causation",
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
	assertPrefixedUUID(t, envelope.CausationID, "cmd")
	if envelope.CausationID == "idem-not-causation" {
		t.Fatalf("causationId must not be derived from idempotency key")
	}
}

func TestDomainRuleViolationDoesNotPublish(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(publisher)
	_, _, err := service.CreateScheduledService(context.Background(), CreateScheduledServiceCommand{
		ServiceRef:        "ss-domain-rule",
		CarrierID:         "car-1",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
		IdempotencyKey:    "idem-domain-rule-service",
		RequestHash:       "hash-service",
	})
	if err != nil {
		t.Fatalf("create scheduled service: %v", err)
	}
	publishedBeforeRejectedChange := len(publisher.envelopes)
	_, _, err = service.CreateServiceSegment(context.Background(), CreateServiceSegmentCommand{
		ScheduledServiceRef: "ss-domain-rule",
		OriginStopRef:       "node-a",
		DestinationStopRef:  "node-a",
		DepartureTime:       time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:         time.Date(2026, 7, 5, 11, 30, 0, 0, time.UTC),
		IdempotencyKey:      "idem-domain-rule-segment",
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
	if !validPrefixedUUID(value, prefix) {
		t.Fatalf("%s is not a canonical %s-prefixed UUID", value, prefix)
	}
}
