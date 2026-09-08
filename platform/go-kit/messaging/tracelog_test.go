package messaging

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"strings"
	"testing"
	"time"

	miniredis "github.com/alicebob/miniredis/v2"
	redis "github.com/redis/go-redis/v9"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
)

const (
	callerTraceID = "4bf92f3577b34da6a3ce929d0e0e4736"
	callerSpanID  = "00f067aa0ba902b7"
)

// The DLQ line is what an operator reads first when an event dies, so it has to
// carry the ids that lead back to the trace. Before this the platform had full
// trace propagation and structured logs with no way to join them: correlating
// five services meant matching wall-clock timestamps across five `kubectl logs`
// invocations by hand.
//
// Driven through the real subscribe path rather than the formatter in isolation,
// and asserted against the *exported* span, so a line carrying some other span's
// ids cannot pass.
func TestDLQWarnLogCarriesTheConsumerSpanIDs(t *testing.T) {
	goruntime.ResetOTelForTest()
	exporter := tracetest.NewInMemoryExporter()
	shutdown, err := goruntime.InitOTelSDK(goruntime.OTelSDKConfig{ServiceName: "go-kit-test", Exporter: exporter})
	if err != nil {
		t.Fatalf("init sdk: %v", err)
	}
	defer func() {
		_ = shutdown(context.Background())
		goruntime.ResetOTelForTest()
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	server := miniredis.RunT(t)
	client := redis.NewClient(&redis.Options{Addr: server.Addr()})
	bus := NewRedisEventBusWithClient(client, RedisConfig{ReadBlock: 10 * time.Millisecond, RecoveryEvery: time.Hour}).
		WithObserver(goruntime.NewOTelObserver(goruntime.OTelObserverConfig{ServiceName: "go-kit-test", TracerName: "go-kit-test"}))
	defer bus.Close()

	envelope, err := NewEventEnvelope("TracedPoisonEvent", "payment", "0194f2e0-7b3e-7610-0284-5c26e8b0c123", map[string]string{"id": "1"}, EnvelopeOptions{Now: time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC)})
	if err != nil {
		t.Fatal(err)
	}
	envelope.Traceparent = fmt.Sprintf("00-%s-%s-01", callerTraceID, callerSpanID)
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

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		messages, err := bus.client.XRange(ctx, stream+DeadLetterSuffix, "-", "+").Result()
		if err != nil {
			t.Fatal(err)
		}
		if len(messages) > 0 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}

	spans := exporter.GetSpans()
	if len(spans) != 1 {
		t.Fatalf("expected one consumer span, got %d", len(spans))
	}
	logText := logBuffer.String()
	if !strings.Contains(logText, "trace_id="+spans[0].SpanContext.TraceID().String()) {
		t.Fatalf("DLQ line must carry the consumer span's trace id: %s", logText)
	}
	// The span id must be this consumer span's own, not the producer's: naming
	// the producer's span points an operator at the wrong service.
	if !strings.Contains(logText, "span_id="+spans[0].SpanContext.SpanID().String()) {
		t.Fatalf("DLQ line must carry the consumer span's span id: %s", logText)
	}
	if strings.Contains(logText, "span_id="+callerSpanID) {
		t.Fatalf("DLQ line reported the producer's span id: %s", logText)
	}
	if !strings.Contains(logText, "trace_id="+callerTraceID) {
		t.Fatalf("DLQ line must stay on the caller's trace: %s", logText)
	}
	if !strings.Contains(logText, "moving message to DLQ") {
		t.Fatalf("the trace fields must prefix, not replace, the message: %s", logText)
	}
	if strings.Contains(logText, "00000000000000000000000000000000") {
		t.Fatalf("an all-zero trace id must never be printed: %s", logText)
	}
}

// Lines logged with no span must be byte-identical to what they were before the
// ids were introduced, so startup, shutdown and background-timer output stays
// readable rather than showing an empty placeholder.
func TestUntracedDLQWarnLogIsUnchanged(t *testing.T) {
	goruntime.ResetOTelForTest()
	defer goruntime.ResetOTelForTest()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	server := miniredis.RunT(t)
	client := redis.NewClient(&redis.Options{Addr: server.Addr()})
	bus := NewRedisEventBusWithClient(client, RedisConfig{ReadBlock: 10 * time.Millisecond, RecoveryEvery: time.Hour})
	defer bus.Close()

	envelope, err := NewEventEnvelope("UntracedPoisonEvent", "payment", "0194f2e0-7b3e-7610-0284-5c26e8b0c123", map[string]string{"id": "1"}, EnvelopeOptions{Now: time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC)})
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

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if strings.Contains(logBuffer.String(), "moving message to DLQ") {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}

	logText := logBuffer.String()
	if !strings.Contains(logText, "WARN service=journey-order") {
		t.Fatalf("an untraced line must be unchanged: %s", logText)
	}
	if strings.Contains(logText, "trace_id=") || strings.Contains(logText, "span_id=") {
		t.Fatalf("no span means no ids at all: %s", logText)
	}
}
