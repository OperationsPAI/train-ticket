package goruntime

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"
)

func TestStandardEndpoints(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := NewGinRouter(GinConfig{Metadata: map[string]string{"serviceId": "sample"}})

	for _, path := range []string{"/health", "/live", "/livez", "/ready", "/readyz", "/metadata"} {
		t.Run(path, func(t *testing.T) {
			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodGet, path, nil)

			router.ServeHTTP(recorder, request)

			if recorder.Code != http.StatusOK {
				t.Fatalf("unexpected status for %s: %d", path, recorder.Code)
			}
			if recorder.Header().Get(RequestIDHeader) == "" {
				t.Fatalf("missing request id response header")
			}
			if recorder.Header().Get(CorrelationIDHeader) == "" {
				t.Fatalf("missing correlation id response header")
			}
		})
	}
}

func TestRequestIDsArePropagated(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := NewGinRouter(GinConfig{RequestIDSource: func() string { return "generated" }})
	router.GET("/ids", func(ctx *gin.Context) {
		ctx.JSON(http.StatusOK, gin.H{
			"requestId":     RequestID(ctx.Request.Context()),
			"correlationId": CorrelationID(ctx.Request.Context()),
		})
	})

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/ids", nil)
	request.Header.Set(RequestIDHeader, "incoming-request")
	request.Header.Set(CorrelationIDHeader, "incoming-correlation")

	router.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("unexpected status: %d", recorder.Code)
	}
	if got := recorder.Header().Get(RequestIDHeader); got != "incoming-request" {
		t.Fatalf("unexpected request id header: %q", got)
	}
	if got := recorder.Header().Get(CorrelationIDHeader); got != "incoming-correlation" {
		t.Fatalf("unexpected correlation id header: %q", got)
	}
}

func TestRequestIDIsGeneratedAndCorrelationIDIsMinted(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := NewGinRouter(GinConfig{RequestIDSource: func() string { return "generated-request" }})

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/health", nil)

	router.ServeHTTP(recorder, request)

	if got := recorder.Header().Get(RequestIDHeader); got != "generated-request" {
		t.Fatalf("unexpected generated request id: %q", got)
	}
	// The messaging contract requires corr-<uuidv7> correlation ids; a missing
	// inbound header mints one instead of reusing the request id.
	got := recorder.Header().Get(CorrelationIDHeader)
	if !strings.HasPrefix(got, "corr-") || len(got) != len("corr-")+36 {
		t.Fatalf("correlation id not corr-<uuid>: %q", got)
	}
}

type recordingObserver struct {
	operations []string
	ended      bool
}

func (observer *recordingObserver) Start(ctx context.Context, operation string) (context.Context, Span) {
	observer.operations = append(observer.operations, operation)
	return ctx, recordingSpan{observer: observer}
}

type recordingSpan struct{ observer *recordingObserver }

func (span recordingSpan) End(error) { span.observer.ended = true }

func TestObserverSeamIsOptIn(t *testing.T) {
	gin.SetMode(gin.TestMode)
	observer := &recordingObserver{}
	router := NewGinRouter(GinConfig{Observer: observer})

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/health", nil)

	router.ServeHTTP(recorder, request)

	if len(observer.operations) != 1 || observer.operations[0] != "/health" {
		t.Fatalf("unexpected operations: %#v", observer.operations)
	}
	if !observer.ended {
		t.Fatalf("expected span to end")
	}
}

func TestInitOTelSDKFromEnvNoopWhenTracingDisabled(t *testing.T) {
	ResetOTelForTest()
	t.Setenv("OTEL_TRACES_EXPORTER", "")
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "")
	t.Setenv("OTEL_SERVICE_NAME", "")

	shutdown, err := InitOTelSDKFromEnv(context.Background(), "runtime-test")
	if err != nil {
		t.Fatalf("unexpected init error: %v", err)
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatalf("unexpected shutdown error: %v", err)
	}

	ctx, span := ObserverFromEnv("runtime-test").Start(context.Background(), "/noop")
	span.End(nil)
	if got := trace.SpanFromContext(ctx).SpanContext(); got.IsValid() {
		t.Fatalf("expected disabled tracing to leave a non-recording span, got %s", got.TraceID())
	}
}

func TestInitOTelSDKWithInMemoryExporterProducesHTTPSpan(t *testing.T) {
	ResetOTelForTest()
	exporter := tracetest.NewInMemoryExporter()
	shutdown, err := InitOTelSDK(OTelSDKConfig{ServiceName: "runtime-test", Exporter: exporter})
	if err != nil {
		t.Fatalf("init sdk: %v", err)
	}
	defer func() {
		_ = shutdown(context.Background())
		ResetOTelForTest()
	}()

	observer := NewOTelObserver(OTelObserverConfig{ServiceName: "runtime-test"})
	ctx, span := observer.Start(ContextWithRequestIDs(context.Background(), "req-1", "corr-1"), "/health")
	SetSpanHTTPAttributes(ctx, http.MethodGet, "/health", http.StatusOK)
	span.End(nil)
	if flusher, ok := span.(interface{ ForceFlush(context.Context) error }); ok {
		if err := flusher.ForceFlush(context.Background()); err != nil {
			t.Fatalf("flush sdk: %v", err)
		}
	}

	spans := exporter.GetSpans()
	if len(spans) != 1 {
		t.Fatalf("expected one span, got %d", len(spans))
	}
	if spans[0].Name != "/health" {
		t.Fatalf("unexpected span name: %q", spans[0].Name)
	}
	attrs := spanAttributes(spans[0].Attributes)
	if attrs["http.route"] != "/health" || attrs["http.request_id"] != "req-1" || attrs["http.correlation_id"] != "corr-1" {
		t.Fatalf("missing HTTP attrs: %#v", attrs)
	}
}

func spanAttributes(attrs []attribute.KeyValue) map[string]string {
	values := map[string]string{}
	for _, attr := range attrs {
		values[string(attr.Key)] = attr.Value.AsString()
	}
	return values
}
