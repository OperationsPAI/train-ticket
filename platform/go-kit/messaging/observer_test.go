package messaging

import (
	"context"
	"errors"
	"testing"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
)

func TestObservedHandlerNoopWithoutOTelEnv(t *testing.T) {
	goruntime.ResetOTelForTest()
	t.Setenv("OTEL_TRACES_EXPORTER", "")
	handled := false
	envelope := testEnvelopeForObserver(t)

	err := ObservedHandler(ObserverFromEnv("go-kit-test"), "events:test", "group-a", func(ctx context.Context, got EventEnvelope) error {
		handled = true
		if StreamFromContext(ctx) != "events:test" || ConsumerGroupFromContext(ctx) != "group-a" {
			t.Fatalf("missing message context")
		}
		if got.EventID != envelope.EventID {
			t.Fatalf("unexpected envelope: %#v", got)
		}
		return nil
	})(context.Background(), envelope)
	if err != nil {
		t.Fatalf("handler returned error: %v", err)
	}
	if !handled {
		t.Fatalf("handler was not called")
	}
}

func TestObservedHandlerEmitsMessagingSpan(t *testing.T) {
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

	envelope := testEnvelopeForObserver(t)
	handlerErr := errors.New("poison")
	err = ObservedHandler(goruntime.NewOTelObserver(goruntime.OTelObserverConfig{ServiceName: "go-kit-test", TracerName: "go-kit-test"}), "events:test", "group-a", func(context.Context, EventEnvelope) error {
		return handlerErr
	})(context.Background(), envelope)
	if !errors.Is(err, handlerErr) {
		t.Fatalf("unexpected handler error: %v", err)
	}
	spans := exporter.GetSpans()
	if len(spans) != 1 {
		t.Fatalf("expected one span, got %d", len(spans))
	}
	attrs := attributesToStrings(spans[0].Attributes)
	for key, want := range map[string]string{
		"stream":        "events:test",
		"consumerGroup": "group-a",
		"eventId":       envelope.EventID,
		"eventType":     envelope.EventType,
		"correlationId": envelope.CorrelationID,
	} {
		if attrs[key] != want {
			t.Fatalf("attribute %s mismatch: got %q want %q attrs=%#v", key, attrs[key], want, attrs)
		}
	}
	if spans[0].Status.Code != codes.Error {
		t.Fatalf("expected ERROR status, got %#v", spans[0].Status)
	}
}

func testEnvelopeForObserver(t *testing.T) EventEnvelope {
	t.Helper()
	envelope, err := NewEventEnvelope("TestEvent", "go-kit-test", "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c444", map[string]string{"ok": "true"})
	if err != nil {
		t.Fatalf("new envelope: %v", err)
	}
	return envelope
}

func attributesToStrings(attrs []attribute.KeyValue) map[string]string {
	values := map[string]string{}
	for _, attr := range attrs {
		values[string(attr.Key)] = attr.Value.AsString()
	}
	return values
}
