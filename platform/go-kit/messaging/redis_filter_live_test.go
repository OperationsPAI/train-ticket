package messaging

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"testing"
	"time"

	redis "github.com/redis/go-redis/v9"
	"github.com/trainticket/greenfield/platform/go-kit/ids"
)

func TestRedisFilteredBatchPreservesRequiredEventsAndRetries(t *testing.T) {
	url := os.Getenv("TEST_REDIS_URL")
	path := os.Getenv("TEST_GO_FILTER_EVENTS")
	if url == "" || path == "" {
		t.Skip("requires TEST_REDIS_URL and TEST_GO_FILTER_EVENTS")
	}
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var events []EventEnvelope
	if err := json.Unmarshal(body, &events); err != nil {
		t.Fatal(err)
	}
	var selected []EventEnvelope
	var required EventEnvelope
	for _, event := range events {
		if event.EventType == "JourneyOrderConfirmed" {
			required = event
		} else if len(selected) < 98 {
			selected = append(selected, event)
		}
	}
	if len(selected) != 98 || required.EventID == "" {
		t.Fatal("real source events are incomplete")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	client, err := NewRedisClient(url)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	bus := NewRedisEventBusWithClient(client, RedisConfig{})
	stream := "test:filtered:" + ids.NewUUIDv7()
	group, consumer := "filter", "reader"
	defer client.Del(context.Background(), stream, stream+DeadLetterSuffix)
	if err := client.XGroupCreateMkStream(ctx, stream, group, "0").Err(); err != nil {
		t.Fatal(err)
	}
	selected = append(selected, required)
	for _, event := range selected {
		body, err := json.Marshal(event)
		if err != nil {
			t.Fatal(err)
		}
		if err := client.XAdd(ctx, &redis.XAddArgs{Stream: stream, Values: map[string]any{EnvelopeField: string(body)}}).Err(); err != nil {
			t.Fatal(err)
		}
	}
	if err := client.XAdd(ctx, &redis.XAddArgs{Stream: stream, Values: map[string]any{EnvelopeField: "{"}}).Err(); err != nil {
		t.Fatal(err)
	}
	batch, err := client.XReadGroup(ctx, &redis.XReadGroupArgs{Group: group, Consumer: consumer, Streams: []string{stream, ">"}, Count: 100}).Result()
	if err != nil {
		t.Fatal(err)
	}
	if len(batch) != 1 || len(batch[0].Messages) != 100 {
		t.Fatalf("unexpected batch: %v", batch)
	}
	calls := 0
	handler := func(_ context.Context, event EventEnvelope) error {
		if event.EventID != required.EventID {
			t.Errorf("unexpected handled event %s", event.EventID)
		}
		calls++
		if calls == 1 {
			return TransientHandlerError(errors.New("dependency unavailable"))
		}
		return nil
	}
	filter := func(event EventEnvelope) bool { return event.EventType == "JourneyOrderConfirmed" }
	bus.processBatch(ctx, stream, group, consumer, batch[0].Messages, handler, filter)
	pending, err := client.XPending(ctx, stream, group).Result()
	if err != nil {
		t.Fatal(err)
	}
	if pending.Count != 1 || calls != 1 {
		t.Fatalf("pending=%d calls=%d", pending.Count, calls)
	}
	if length := client.XLen(ctx, stream+DeadLetterSuffix).Val(); length != 1 {
		t.Fatalf("malformed message DLQ count=%d", length)
	}
	if err := client.Do(ctx, "XCLAIM", stream, group, consumer, 0, pending.Lower, "IDLE", 60001, "JUSTID").Err(); err != nil {
		t.Fatal(err)
	}
	bus.recoverPending(ctx, stream, group, consumer, handler, filter)
	if pending := client.XPending(ctx, stream, group).Val(); pending.Count != 0 || calls != 2 {
		t.Fatalf("pending=%d calls=%d", pending.Count, calls)
	}
}
