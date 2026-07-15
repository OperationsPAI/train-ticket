package application

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

func TestConsumesAncillaryFulfillmentReadyWithDedup(t *testing.T) {
	repo := NewInMemoryRepository()
	log := NewInMemoryConsumedEventLog()
	service := NewService(repo, NoopPublisher{}, log, nil, nil)
	envelope := EventEnvelope{
		EventID:       "evt-anc-ready-1",
		EventType:     "AncillaryOrderItemFulfillmentReady",
		Producer:      "ancillary-service",
		SchemaVersion: 1,
		Payload: json.RawMessage(`{
			"ancillaryOrderItemId":"aoi-123",
			"journeyOrderId":"ord-123",
			"travelerRef":"tvl-123",
			"segmentRef":"seg-123",
			"entitlementRef":"ent-123",
			"providerRef":"voucher-123",
			"status":"FULFILLMENT_READY",
			"readyAt":"2026-07-09T10:00:00Z"
		}`),
	}
	if err := service.HandleSubscribedEvent(context.Background(), envelope); err != nil {
		t.Fatalf("first ready event failed: %v", err)
	}
	if err := service.HandleSubscribedEvent(context.Background(), envelope); err != nil {
		t.Fatalf("duplicate ready event should be ack-safe: %v", err)
	}
	handoff, err := repo.FindExternalFulfillmentHandoff(context.Background(), "ancillary-service", "aoi-123")
	if err != nil {
		t.Fatalf("handoff not stored: %v", err)
	}
	if handoff.Status != "FULFILLMENT_READY" || handoff.ProviderRef != "voucher-123" || handoff.ReadyAt == nil {
		t.Fatalf("unexpected handoff: %#v", handoff)
	}
	if got := handoff.LastEventID; got != envelope.EventID {
		t.Fatalf("last event not tracked: %s", got)
	}
}

func TestConsumesDispatchRideLifecycleFacts(t *testing.T) {
	repo := NewInMemoryRepository()
	service := NewService(repo, NoopPublisher{}, NewInMemoryConsumedEventLog(), nil, nil)
	events := []EventEnvelope{
		{EventID: "evt-driver-arrived", EventType: "DriverArrived", Producer: "dispatch", SchemaVersion: 1, Payload: json.RawMessage(`{"rideRequestId":"rrq-123","rideAssignmentId":"ras-123","travelerRef":"tvl-123","arrivedAt":"2026-07-09T10:00:00Z","status":"DRIVER_ARRIVED"}`)},
		{EventID: "evt-ride-started", EventType: "RideStarted", Producer: "dispatch", SchemaVersion: 1, Payload: json.RawMessage(`{"rideRequestId":"rrq-123","rideAssignmentId":"ras-123","travelerRef":"tvl-123","startedAt":"2026-07-09T10:05:00Z","status":"PICKED_UP"}`)},
		{EventID: "evt-ride-ended", EventType: "RideEnded", Producer: "dispatch", SchemaVersion: 1, Payload: json.RawMessage(`{"rideRequestId":"rrq-123","rideAssignmentId":"ras-123","travelerRef":"tvl-123","startedAt":"2026-07-09T10:05:00Z","endedAt":"2026-07-09T10:25:00Z","status":"COMPLETED"}`)},
	}
	for _, envelope := range events {
		if err := service.HandleSubscribedEvent(context.Background(), envelope); err != nil {
			t.Fatalf("%s failed: %v", envelope.EventType, err)
		}
	}
	handoff, err := repo.FindExternalFulfillmentHandoff(context.Background(), "dispatch", "rrq-123")
	if err != nil {
		t.Fatalf("handoff not stored: %v", err)
	}
	if handoff.Status != "COMPLETED" || handoff.ArrivedAt == nil || handoff.StartedAt == nil || handoff.CompletedAt == nil {
		t.Fatalf("unexpected dispatch lifecycle: %#v", handoff)
	}
	if !handoff.CompletedAt.Equal(time.Date(2026, 7, 9, 10, 25, 0, 0, time.UTC)) {
		t.Fatalf("unexpected completion time: %s", handoff.CompletedAt)
	}
}

func TestUnknownDispatchStatusAckSkips(t *testing.T) {
	repo := NewInMemoryRepository()
	service := NewService(repo, NoopPublisher{}, NewInMemoryConsumedEventLog(), nil, nil)
	envelope := EventEnvelope{EventID: "evt-driver-arrived-unknown", EventType: "DriverArrived", Producer: "dispatch", SchemaVersion: 1, Payload: json.RawMessage(`{"rideRequestId":"rrq-unknown","arrivedAt":"2026-07-09T10:00:00Z","status":"ARRIVED"}`)}
	if err := service.HandleSubscribedEvent(context.Background(), envelope); err != nil {
		t.Fatalf("unknown but conformant status should ack-skip: %v", err)
	}
	if _, err := repo.FindExternalFulfillmentHandoff(context.Background(), "dispatch", "rrq-unknown"); err == nil {
		t.Fatalf("unknown status should not store a handoff")
	}
}
