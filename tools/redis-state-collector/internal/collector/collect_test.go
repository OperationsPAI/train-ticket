package collector

import (
	"context"
	"testing"

	miniredis "github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func TestCollectReportsLagPendingAndDeadLettersFromRedisState(t *testing.T) {
	server := miniredis.RunT(t)
	client := redis.NewClient(&redis.Options{Addr: server.Addr()})
	t.Cleanup(func() { _ = client.Close() })
	ctx := context.Background()

	if err := client.XAdd(ctx, &redis.XAddArgs{Stream: "events:journey-order", Values: map[string]any{"envelope": `{"eventType":"JourneyOrderCreated"}`}}).Err(); err != nil {
		t.Fatalf("seed stream: %v", err)
	}
	if err := client.XGroupCreate(ctx, "events:journey-order", "booking-orchestration", "0").Err(); err != nil {
		t.Fatalf("create group: %v", err)
	}
	if err := client.XAdd(ctx, &redis.XAddArgs{
		Stream: "events:journey-order:dlq",
		Values: map[string]any{"consumerGroup": "notification", "envelope": `{"eventType":"PaymentCaptured"}`},
	}).Err(); err != nil {
		t.Fatalf("seed dead letter: %v", err)
	}
	// A key outside the event namespace must never become a label.
	if err := client.XAdd(ctx, &redis.XAddArgs{Stream: "unrelated", Values: map[string]any{"envelope": "{}"}}).Err(); err != nil {
		t.Fatalf("seed unrelated key: %v", err)
	}

	snapshot, err := Collect(ctx, client, DefaultOptions())
	if err != nil {
		t.Fatalf("Collect: %v", err)
	}

	if len(snapshot.Groups) != 1 {
		t.Fatalf("groups = %+v, want exactly the events: stream", snapshot.Groups)
	}
	group := snapshot.Groups[0]
	if group.Stream != "events:journey-order" || group.ConsumerGroup != "booking-orchestration" {
		t.Fatalf("unexpected group identity: %+v", group)
	}
	if group.Lag != 1 {
		t.Fatalf("lag = %d, want 1: the group has never been handed the only entry", group.Lag)
	}
	if !group.HasOldest {
		t.Fatalf("an undelivered entry must produce an oldest age: %+v", group)
	}

	if len(snapshot.DeadLetters) != 1 {
		t.Fatalf("dead letters = %+v, want one attributed entry", snapshot.DeadLetters)
	}
	deadLetter := snapshot.DeadLetters[0]
	if deadLetter.Stream != "events:journey-order" || deadLetter.ConsumerGroup != "notification" || deadLetter.EventType != "PaymentCaptured" || deadLetter.Entries != 1 {
		t.Fatalf("unexpected dead-letter state: %+v", deadLetter)
	}
}

// TestCollectOnAnEmptyTransportIsNotAnError pins the difference between "there
// is nothing to report" and "the read failed": the caller publishes an empty
// snapshot only when Redis answered.
func TestCollectOnAnEmptyTransportIsNotAnError(t *testing.T) {
	server := miniredis.RunT(t)
	client := redis.NewClient(&redis.Options{Addr: server.Addr()})
	t.Cleanup(func() { _ = client.Close() })

	snapshot, err := Collect(context.Background(), client, DefaultOptions())

	if err != nil {
		t.Fatalf("Collect on an empty transport must not fail: %v", err)
	}
	if len(snapshot.Groups) != 0 || len(snapshot.DeadLetters) != 0 {
		t.Fatalf("expected an empty snapshot, got %+v", snapshot)
	}
}
