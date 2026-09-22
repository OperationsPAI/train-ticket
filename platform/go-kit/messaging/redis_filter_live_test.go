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
	bus.recoverPending(ctx, stream, group, consumer, handler, filter, "0-0")
	if pending := client.XPending(ctx, stream, group).Val(); pending.Count != 0 || calls != 2 {
		t.Fatalf("pending=%d calls=%d", pending.Count, calls)
	}
}

func TestRedisRecoveryContinuesBeyondAnActivePendingPage(t *testing.T) {
	url, path := os.Getenv("TEST_REDIS_URL"), os.Getenv("TEST_GO_FILTER_EVENTS")
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
	if len(events) == 0 {
		t.Fatal("real source events are missing")
	}
	body, err = json.Marshal(events[0])
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	client, err := NewRedisClient(url)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	bus := NewRedisEventBusWithClient(client, RedisConfig{})
	stream, group := "test:recovery:"+ids.NewUUIDv7(), "recovery"
	defer client.Del(context.Background(), stream)
	if err := client.XGroupCreateMkStream(ctx, stream, group, "0").Err(); err != nil {
		t.Fatal(err)
	}
	pipe := client.Pipeline()
	for index := 0; index < 1200; index++ {
		pipe.XAdd(ctx, &redis.XAddArgs{Stream: stream, Values: map[string]any{EnvelopeField: string(body)}})
	}
	if _, err := pipe.Exec(ctx); err != nil {
		t.Fatal(err)
	}
	batch, err := client.XReadGroup(ctx, &redis.XReadGroupArgs{Group: group, Consumer: "original", Streams: []string{stream, ">"}, Count: 1200}).Result()
	if err != nil {
		t.Fatal(err)
	}
	if len(batch) != 1 || len(batch[0].Messages) != 1200 {
		t.Fatal("missing pending messages")
	}
	last := batch[0].Messages[1199].ID
	if err := client.Do(ctx, "XCLAIM", stream, group, "original", 0, last, "IDLE", 60001, "JUSTID").Err(); err != nil {
		t.Fatal(err)
	}
	calls := 0
	handler := func(context.Context, EventEnvelope) error { calls++; return nil }
	cursor := bus.recoverPending(ctx, stream, group, "reader", handler, nil, "0-0")
	if cursor == "0-0" || calls != 0 {
		t.Fatalf("first page cursor=%s calls=%d", cursor, calls)
	}
	cursor = bus.recoverPending(ctx, stream, group, "reader", handler, nil, cursor)
	if cursor != "0-0" || calls != 1 {
		t.Fatalf("second page cursor=%s calls=%d", cursor, calls)
	}
	if pending := client.XPending(ctx, stream, group).Val(); pending.Count != 1199 {
		t.Fatalf("pending=%d", pending.Count)
	}
}

func TestRedisDLQWriteFailurePreservesOriginalMessage(t *testing.T) {
	url := os.Getenv("TEST_REDIS_URL")
	if url == "" {
		t.Skip("requires TEST_REDIS_URL")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	client, err := NewRedisClient(url)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	bus := NewRedisEventBusWithClient(client, RedisConfig{})
	stream := "test:dlq-failure:" + ids.NewUUIDv7()
	group, consumer := "dlq-failure", "reader"
	defer client.Del(context.Background(), stream, stream+DeadLetterSuffix)
	if err := client.XGroupCreateMkStream(ctx, stream, group, "0").Err(); err != nil {
		t.Fatal(err)
	}
	if err := client.XAdd(ctx, &redis.XAddArgs{Stream: stream, Values: map[string]any{EnvelopeField: "{"}}).Err(); err != nil {
		t.Fatal(err)
	}
	batch, err := client.XReadGroup(ctx, &redis.XReadGroupArgs{Group: group, Consumer: consumer, Streams: []string{stream, ">"}, Count: 1}).Result()
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Set(ctx, stream+DeadLetterSuffix, "unavailable-stream-type", 0).Err(); err != nil {
		t.Fatal(err)
	}
	handler := func(context.Context, EventEnvelope) error { t.Error("malformed envelope reached handler"); return nil }
	bus.processMessage(ctx, stream, group, consumer, batch[0].Messages[0], handler)
	pending, err := client.XPending(ctx, stream, group).Result()
	if err != nil {
		t.Fatal(err)
	}
	if pending.Count != 1 {
		t.Fatalf("original message was acknowledged after DLQ failure: %d", pending.Count)
	}
	if err := client.Del(ctx, stream+DeadLetterSuffix).Err(); err != nil {
		t.Fatal(err)
	}
	bus.processMessage(ctx, stream, group, consumer, batch[0].Messages[0], handler)
	if pending := client.XPending(ctx, stream, group).Val(); pending.Count != 0 {
		t.Fatalf("pending=%d", pending.Count)
	}
	entries, err := client.XRange(ctx, stream+DeadLetterSuffix, "-", "+").Result()
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Values[EnvelopeField] != "{" {
		t.Fatalf("original envelope missing from DLQ: %v", entries)
	}
}
