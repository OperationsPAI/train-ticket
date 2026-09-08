package goruntime

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"
)

const (
	testTraceID = "4bf92f3577b34da6a3ce929d0e0e4736"
	testSpanID  = "00f067aa0ba902b7"
)

func contextWithSpan(t *testing.T, traceID, spanID string) context.Context {
	t.Helper()
	tid, err := trace.TraceIDFromHex(traceID)
	if err != nil {
		t.Fatalf("trace id: %v", err)
	}
	sid, err := trace.SpanIDFromHex(spanID)
	if err != nil {
		t.Fatalf("span id: %v", err)
	}
	return trace.ContextWithSpanContext(context.Background(), trace.NewSpanContext(trace.SpanContextConfig{
		TraceID:    tid,
		SpanID:     sid,
		TraceFlags: trace.FlagsSampled,
		Remote:     true,
	}))
}

func TestTraceIDsReportsAValidSpan(t *testing.T) {
	traceID, spanID, ok := TraceIDs(contextWithSpan(t, testTraceID, testSpanID))
	if !ok {
		t.Fatal("a valid span context must report ids")
	}
	if traceID != testTraceID || spanID != testSpanID {
		t.Fatalf("got %s/%s, want %s/%s", traceID, spanID, testTraceID, testSpanID)
	}
}

// An absent or non-recording span yields an all-zero trace id. Printing that is
// worse than printing nothing: it looks like a real id and joins every unrelated
// line in the log together.
func TestTraceIDsReportsNothingWithoutASpan(t *testing.T) {
	for name, ctx := range map[string]context.Context{
		"background": context.Background(),
		"nil":        nil,
	} {
		if _, _, ok := TraceIDs(ctx); ok {
			t.Errorf("%s context must not report ids", name)
		}
		if fields := TraceLogFields(ctx); fields != "" {
			t.Errorf("%s context rendered %q, want empty", name, fields)
		}
	}
}

func TestTraceIDsReportsNothingForAnAllZeroSpanContext(t *testing.T) {
	// The exact shape a non-recording span produces, asserted directly so the
	// guard cannot be weakened to "there is a span context" and still pass.
	ctx := trace.ContextWithSpanContext(context.Background(), trace.NewSpanContext(trace.SpanContextConfig{}))
	if _, _, ok := TraceIDs(ctx); ok {
		t.Fatal("an all-zero span context must not report ids")
	}
	fields := TraceLogFields(ctx)
	if fields != "" {
		t.Fatalf("rendered %q for an all-zero span context, want empty", fields)
	}
	if strings.Contains(fields, "0000") {
		t.Fatalf("an all-zero id must never be rendered: %q", fields)
	}
}

func TestTraceLogFieldsUsesTheOpenTelemetryFieldNames(t *testing.T) {
	// These must match java-kit's MDC keys exactly; traceId or trace-id would
	// not join.
	if TraceIDField != "trace_id" || SpanIDField != "span_id" {
		t.Fatalf("field names drifted: %s/%s", TraceIDField, SpanIDField)
	}
	want := fmt.Sprintf("trace_id=%s span_id=%s ", testTraceID, testSpanID)
	if got := TraceLogFields(contextWithSpan(t, testTraceID, testSpanID)); got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

// Stands in for a real call site: the rendered fields must prefix the message
// without displacing it, and must vanish entirely outside a span.
func TestTraceLogFieldsPrefixesALogLine(t *testing.T) {
	traced := fmt.Sprintf("%sservice=dispatch scan failed", TraceLogFields(contextWithSpan(t, testTraceID, testSpanID)))
	if !strings.HasPrefix(traced, "trace_id="+testTraceID) || !strings.HasSuffix(traced, "service=dispatch scan failed") {
		t.Fatalf("unexpected traced line: %q", traced)
	}
	untraced := fmt.Sprintf("%sservice=dispatch scan failed", TraceLogFields(context.Background()))
	if untraced != "service=dispatch scan failed" {
		t.Fatalf("an untraced line must be unchanged, got %q", untraced)
	}
}

// The HTTP server span must reach the handler's context, or nothing a handler
// logs can be joined to the request's trace. Unlike the Rust and TypeScript
// kits, Go's tracer puts the span into the context it returns, so this already
// held -- this test pins it so a future refactor cannot silently drop it.
func TestHandlerContextCarriesTheServerSpanIDs(t *testing.T) {
	gin.SetMode(gin.TestMode)
	ResetOTelForTest()
	exporter := tracetest.NewInMemoryExporter()
	shutdown, err := InitOTelSDK(OTelSDKConfig{ServiceName: "tracelog-test", Exporter: exporter})
	if err != nil {
		t.Fatalf("init sdk: %v", err)
	}
	t.Cleanup(func() {
		_ = shutdown(context.Background())
		ResetOTelForTest()
	})

	var got string
	router := gin.New()
	router.Use(RequestContextMiddleware(nil))
	router.Use(TracingMiddleware(NewOTelObserver(OTelObserverConfig{ServiceName: "tracelog-test"})))
	router.GET("/observed", func(c *gin.Context) {
		got = TraceLogFields(c.Request.Context())
		c.Status(http.StatusOK)
	})

	request := httptest.NewRequest(http.MethodGet, "/observed", nil)
	request.Header.Set("traceparent", fmt.Sprintf("00-%s-%s-01", testTraceID, testSpanID))
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status %d", recorder.Code)
	}
	if !strings.Contains(got, "trace_id="+testTraceID) {
		t.Fatalf("handler must see the caller's trace id, got %q", got)
	}
	// The span id must be this server span's own, not the caller's: a log line
	// naming the caller's span points an operator at the wrong service.
	if strings.Contains(got, "span_id="+testSpanID) {
		t.Fatalf("handler reported the caller's span id rather than the server span's: %q", got)
	}
	spans := exporter.GetSpans()
	if len(spans) != 1 {
		t.Fatalf("expected one server span, got %d", len(spans))
	}
	if !strings.Contains(got, "span_id="+spans[0].SpanContext.SpanID().String()) {
		t.Fatalf("handler must see this server span's id, got %q", got)
	}
}
