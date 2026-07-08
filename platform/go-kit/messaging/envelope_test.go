package messaging

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log"
	"strings"
	"testing"
	"time"

	miniredis "github.com/alicebob/miniredis/v2"
	redis "github.com/redis/go-redis/v9"
)

func TestNewEventEnvelopeCanonicalShape(t *testing.T) {
	envelope, err := NewEventEnvelope("ThingHappened", "test-producer", "0194f2e0-7b3e-7610-0284-5c26e8b0c123", map[string]string{"thingId": "thing-1"}, EnvelopeOptions{Now: time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC), CausationID: "0194f2e0-7b3e-7610-0284-5c26e8b0c124"})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(envelope.EventID, "evt-") || envelope.CorrelationID != "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c123" || envelope.CausationID != "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c124" {
		t.Fatalf("unexpected ids: %#v", envelope)
	}
	body, err := json.Marshal(envelope)
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(body, &fields); err != nil {
		t.Fatal(err)
	}
	if len(fields) != 8 {
		t.Fatalf("envelope must have exactly 8 fields, got %d: %s", len(fields), body)
	}
	for _, name := range []string{"eventId", "eventType", "occurredAt", "correlationId", "causationId", "producer", "schemaVersion", "payload"} {
		if _, ok := fields[name]; !ok {
			t.Fatalf("missing envelope field %s in %s", name, body)
		}
	}
}

func TestNewEventEnvelopeOmitsOptionalCausationID(t *testing.T) {
	envelope, err := NewEventEnvelope("ThingHappened", "test-producer", "0194f2e0-7b3e-7610-0284-5c26e8b0c123", map[string]string{"thingId": "thing-1"}, EnvelopeOptions{Now: time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC)})
	if err != nil {
		t.Fatal(err)
	}
	if envelope.CausationID != "" {
		t.Fatalf("causationId should be absent, got %q", envelope.CausationID)
	}
	body, err := json.Marshal(envelope)
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(body, &fields); err != nil {
		t.Fatal(err)
	}
	if _, ok := fields["causationId"]; ok {
		t.Fatalf("causationId must be omitted when absent: %s", body)
	}
	if len(fields) != 7 {
		t.Fatalf("envelope without causationId must have 7 fields, got %d: %s", len(fields), body)
	}
}

func TestDeadLetterFieldsIncludeAttributionMetadata(t *testing.T) {
	fields := dlqFields(`{"eventId":"evt-1"}`, "go-kit", "consumer-a", truncateFailureReason(FatalHandlerError(errors.New("poison"))), 5)
	if fields[EnvelopeField] != `{"eventId":"evt-1"}` {
		t.Fatalf("missing envelope field: %#v", fields)
	}
	if fields["consumerGroup"] != "go-kit" || fields["consumerName"] != "consumer-a" {
		t.Fatalf("missing attribution fields: %#v", fields)
	}
	if fields["failureReason"] != "messaging.HandlerError: poison" || fields["attempts"] != "5" {
		t.Fatalf("missing failure details: %#v", fields)
	}
	if _, err := time.Parse(time.RFC3339Nano, fields["deadLetteredAt"].(string)); err != nil {
		t.Fatalf("deadLetteredAt must be RFC3339 UTC: %v", err)
	}
}

func TestRedisSubscriptionFatalHandlerMovesMessageToDLQWithMetadataAndWarnLog(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	server := miniredis.RunT(t)
	client := redis.NewClient(&redis.Options{Addr: server.Addr()})
	bus := NewRedisEventBusWithClient(client, RedisConfig{ReadBlock: 10 * time.Millisecond, RecoveryEvery: time.Hour})
	defer bus.Close()

	envelope, err := NewEventEnvelope("PoisonEvent", "payment", "0194f2e0-7b3e-7610-0284-5c26e8b0c123", map[string]string{"id": "1"}, EnvelopeOptions{Now: time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC)})
	if err != nil {
		t.Fatal(err)
	}
	body, err := json.Marshal(envelope)
	if err != nil {
		t.Fatal(err)
	}

	var logBuffer bytes.Buffer
	originalWriter := log.Writer()
	log.SetOutput(&logBuffer)
	defer log.SetOutput(originalWriter)

	stream := StreamName("payment")
	if err := bus.Subscribe(ctx, Subscription{Streams: []string{"payment"}, Group: "journey-order", ConsumerName: "consumer-1"}, func(context.Context, EventEnvelope) error {
		return FatalHandlerError(errors.New("poison root cause"))
	}); err != nil {
		t.Fatal(err)
	}
	if err := bus.client.XAdd(ctx, &redis.XAddArgs{Stream: stream, Values: map[string]any{EnvelopeField: string(body)}}).Err(); err != nil {
		t.Fatal(err)
	}

	var dlq []redis.XMessage
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		messages, err := bus.client.XRange(ctx, stream+DeadLetterSuffix, "-", "+").Result()
		if err != nil {
			t.Fatal(err)
		}
		if len(messages) > 0 {
			dlq = messages
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if len(dlq) != 1 {
		t.Fatalf("expected one DLQ message, got %d", len(dlq))
	}
	fields := dlq[0].Values
	if fields["consumerGroup"] != "journey-order" || fields["consumerName"] != "consumer-1" {
		t.Fatalf("missing attribution metadata: %#v", fields)
	}
	if !strings.Contains(fields["failureReason"].(string), "poison root cause") || fields["attempts"] != "1" {
		t.Fatalf("missing failure metadata: %#v", fields)
	}
	if _, err := time.Parse(time.RFC3339Nano, fields["deadLetteredAt"].(string)); err != nil {
		t.Fatalf("bad deadLetteredAt: %v", err)
	}
	if logText := logBuffer.String(); !strings.Contains(logText, "WARN service=journey-order") || !strings.Contains(logText, envelope.EventID) || !strings.Contains(logText, "poison root cause") {
		t.Fatalf("missing WARN DLQ log: %s", logText)
	}
}

func TestRedisSubscriptionTransientHandlerLogsRetryPath(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	server := miniredis.RunT(t)
	client := redis.NewClient(&redis.Options{Addr: server.Addr()})
	bus := NewRedisEventBusWithClient(client, RedisConfig{ReadBlock: 10 * time.Millisecond, RecoveryEvery: time.Hour})
	defer bus.Close()

	envelope, err := NewEventEnvelope("RetryEvent", "payment", "0194f2e0-7b3e-7610-0284-5c26e8b0c123", map[string]string{"id": "1"}, EnvelopeOptions{Now: time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC)})
	if err != nil {
		t.Fatal(err)
	}
	body, err := json.Marshal(envelope)
	if err != nil {
		t.Fatal(err)
	}
	var logBuffer bytes.Buffer
	originalWriter := log.Writer()
	log.SetOutput(&logBuffer)
	defer log.SetOutput(originalWriter)

	stream := StreamName("payment")
	if err := bus.Subscribe(ctx, Subscription{Streams: []string{"payment"}, Group: "journey-order", ConsumerName: "consumer-1"}, func(context.Context, EventEnvelope) error {
		return TransientHandlerError(errors.New("redis temporarily unavailable"))
	}); err != nil {
		t.Fatal(err)
	}
	if err := bus.client.XAdd(ctx, &redis.XAddArgs{Stream: stream, Values: map[string]any{EnvelopeField: string(body)}}).Err(); err != nil {
		t.Fatal(err)
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && !strings.Contains(logBuffer.String(), "handler transient failure") {
		time.Sleep(10 * time.Millisecond)
	}
	logText := logBuffer.String()
	if !strings.Contains(logText, "handler transient failure") || !strings.Contains(logText, envelope.EventID) || !strings.Contains(logText, "redis temporarily unavailable") {
		t.Fatalf("missing transient retry log: %s", logText)
	}
	if dlq, err := bus.client.XRange(ctx, stream+DeadLetterSuffix, "-", "+").Result(); err != nil || len(dlq) != 0 {
		t.Fatalf("transient retry should not DLQ: messages=%#v err=%v", dlq, err)
	}
}

func TestRedisSubscriptionDuplicateAckSkipLogs(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	server := miniredis.RunT(t)
	client := redis.NewClient(&redis.Options{Addr: server.Addr()})
	bus := NewRedisEventBusWithClient(client, RedisConfig{ReadBlock: 10 * time.Millisecond, RecoveryEvery: time.Hour})
	defer bus.Close()

	envelope, err := NewEventEnvelope("DuplicateEvent", "payment", "0194f2e0-7b3e-7610-0284-5c26e8b0c123", map[string]string{"id": "1"}, EnvelopeOptions{Now: time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC)})
	if err != nil {
		t.Fatal(err)
	}
	body, err := json.Marshal(envelope)
	if err != nil {
		t.Fatal(err)
	}
	var logBuffer bytes.Buffer
	originalWriter := log.Writer()
	log.SetOutput(&logBuffer)
	defer log.SetOutput(originalWriter)

	stream := StreamName("payment")
	if err := bus.Subscribe(ctx, Subscription{Streams: []string{"payment"}, Group: "journey-order", ConsumerName: "consumer-1"}, func(context.Context, EventEnvelope) error {
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if err := bus.client.XAdd(ctx, &redis.XAddArgs{Stream: stream, Values: map[string]any{EnvelopeField: string(body)}}).Err(); err != nil {
			t.Fatal(err)
		}
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && !strings.Contains(logBuffer.String(), "duplicate event already processed") {
		time.Sleep(10 * time.Millisecond)
	}
	logText := logBuffer.String()
	if !strings.Contains(logText, "duplicate event already processed") || !strings.Contains(logText, envelope.EventID) {
		t.Fatalf("missing duplicate ack-skip log: %s", logText)
	}
}
