package messaging

import (
	"context"
	"testing"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
)

func TestSubscriberDedupsDuplicateEventIDBeforeHandling(t *testing.T) {
	bus := kitmsg.NewInMemoryEventBus()
	sub := kitmsg.Subscription{Streams: []string{"supplier-catalog"}, Group: "supplier-catalog", ConsumerName: "test"}
	calls := 0
	dedup := kitmsg.NewInMemoryDedupStore()
	if err := bus.Subscribe(context.Background(), sub, kitmsg.DeduplicatingHandler(dedup, func(context.Context, kitmsg.EventEnvelope) error {
		calls++
		return nil
	})); err != nil {
		t.Fatal(err)
	}
	envelope, err := kitmsg.NewEventEnvelope("SupplierRegistered", "supplier-catalog", "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c001", map[string]string{"supplierId": "sup-1"})
	if err != nil {
		t.Fatal(err)
	}
	if err := bus.Publish(context.Background(), envelope); err != nil {
		t.Fatal(err)
	}
	if err := bus.Publish(context.Background(), envelope); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("expected one handler call, got %d", calls)
	}
}

func TestInMemoryBusPublishesWithoutCausationID(t *testing.T) {
	bus := kitmsg.NewInMemoryEventBus()
	calls := 0
	if err := bus.Subscribe(context.Background(), kitmsg.Subscription{Streams: []string{"supplier-catalog"}, Group: "supplier-catalog", ConsumerName: "test"}, func(_ context.Context, envelope kitmsg.EventEnvelope) error {
		calls++
		if envelope.CausationID != "" {
			t.Fatalf("causationId should be optional, got %q", envelope.CausationID)
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	envelope, err := kitmsg.NewEventEnvelope("SupplierRegistered", "supplier-catalog", "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c002", map[string]string{"supplierId": "sup-1"})
	if err != nil {
		t.Fatal(err)
	}
	if err := bus.Publish(context.Background(), envelope); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("expected one handler call, got %d", calls)
	}
}
