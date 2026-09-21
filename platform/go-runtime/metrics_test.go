package goruntime

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"go.opentelemetry.io/otel/attribute"
	metricsdk "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
)

func TestInitOTelMetricsFromEnvNoopWhenMetricsDisabled(t *testing.T) {
	ResetOTelMetricsForTest()
	t.Setenv("OTEL_METRICS_EXPORTER", "none")
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4317")

	shutdown, err := InitOTelMetricsFromEnv(context.Background(), "runtime-test")
	if err != nil {
		t.Fatalf("unexpected init error: %v", err)
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatalf("unexpected shutdown error: %v", err)
	}

	metrics, err := HTTPMetricsFromEnv("runtime-test")
	if err != nil {
		t.Fatalf("unexpected metrics error: %v", err)
	}
	if metrics != nil {
		t.Fatalf("expected no HTTP instruments with metrics disabled")
	}
}

// Go runtime metrics must arrive under the names the Go semantic conventions
// define, since those are what a reader querying a Go service looks for.
func TestInitOTelMetricsPublishesGoRuntimeMetrics(t *testing.T) {
	ResetOTelMetricsForTest()
	reader := metricsdk.NewManualReader()
	shutdown, err := InitOTelMetrics(OTelMetricsConfig{ServiceName: "runtime-test", Reader: reader})
	if err != nil {
		t.Fatalf("init metrics: %v", err)
	}
	defer func() {
		_ = shutdown(context.Background())
		ResetOTelMetricsForTest()
	}()

	names := collectedMetricNames(t, reader)
	for _, expected := range []string{"go.memory.used", "go.goroutine.count", "go.memory.gc.goal"} {
		if !names[expected] {
			t.Fatalf("missing %s; collected %v", expected, names)
		}
	}
}

func TestHTTPServerMetricsRecordDurationByRoute(t *testing.T) {
	ResetOTelMetricsForTest()
	reader := metricsdk.NewManualReader()
	provider := metricsdk.NewMeterProvider(metricsdk.WithReader(reader))
	defer func() { _ = provider.Shutdown(context.Background()) }()

	metrics, err := NewHTTPServerMetrics(provider.Meter("test"))
	if err != nil {
		t.Fatalf("build instruments: %v", err)
	}

	gin.SetMode(gin.TestMode)
	router := NewGinRouter(GinConfig{HTTPMetrics: metrics})
	router.GET("/api/v1/places/:placeId", func(ctx *gin.Context) { ctx.Status(http.StatusOK) })

	router.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/api/v1/places/plc-1", nil))

	duration := collectMetric(t, reader, "http.server.request.duration")
	if duration.Unit != "s" {
		t.Fatalf("unexpected unit: %q", duration.Unit)
	}
	histogram, ok := duration.Data.(metricdata.Histogram[float64])
	if !ok {
		t.Fatalf("expected a float histogram, got %T", duration.Data)
	}
	if len(histogram.DataPoints) != 1 {
		t.Fatalf("expected one data point, got %d", len(histogram.DataPoints))
	}
	point := histogram.DataPoints[0]
	if point.Count != 1 {
		t.Fatalf("expected one recorded request, got %d", point.Count)
	}
	// The route pattern rather than the path: /api/v1/places/plc-1 would be one
	// series per place id.
	assertAttribute(t, point.Attributes, "http.route", "/api/v1/places/:placeId")
	assertAttribute(t, point.Attributes, "http.request.method", "GET")
	assertAttribute(t, point.Attributes, "http.response.status_code", "200")
}

// An unrouted request must carry no http.route. The path comes from the caller,
// so recording it would let anyone reaching the service create series at will.
func TestHTTPServerMetricsOmitRouteForUnmatchedRequest(t *testing.T) {
	ResetOTelMetricsForTest()
	reader := metricsdk.NewManualReader()
	provider := metricsdk.NewMeterProvider(metricsdk.WithReader(reader))
	defer func() { _ = provider.Shutdown(context.Background()) }()

	metrics, err := NewHTTPServerMetrics(provider.Meter("test"))
	if err != nil {
		t.Fatalf("build instruments: %v", err)
	}

	gin.SetMode(gin.TestMode)
	router := NewGinRouter(GinConfig{HTTPMetrics: metrics})
	router.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/no/such/path/aaa", nil))

	duration := collectMetric(t, reader, "http.server.request.duration")
	histogram := duration.Data.(metricdata.Histogram[float64])
	for _, point := range histogram.DataPoints {
		if _, present := point.Attributes.Value("http.route"); present {
			t.Fatalf("unmatched request recorded a route: %v", point.Attributes)
		}
	}
}

// A nil HTTPMetrics is how a service with metrics disabled runs, so the router
// must serve normally without any instruments.
func TestGinRouterServesWithoutMetrics(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := NewGinRouter(GinConfig{})

	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/health", nil))

	if recorder.Code != http.StatusOK {
		t.Fatalf("unexpected status: %d", recorder.Code)
	}
}

func collectedMetricNames(t *testing.T, reader metricsdk.Reader) map[string]bool {
	t.Helper()
	var collected metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &collected); err != nil {
		t.Fatalf("collect: %v", err)
	}
	names := map[string]bool{}
	for _, scope := range collected.ScopeMetrics {
		for _, metric := range scope.Metrics {
			names[metric.Name] = true
		}
	}
	return names
}

func collectMetric(t *testing.T, reader metricsdk.Reader, name string) metricdata.Metrics {
	t.Helper()
	var collected metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &collected); err != nil {
		t.Fatalf("collect: %v", err)
	}
	for _, scope := range collected.ScopeMetrics {
		for _, metric := range scope.Metrics {
			if metric.Name == name {
				return metric
			}
		}
	}
	t.Fatalf("metric %s was not collected", name)
	return metricdata.Metrics{}
}

func assertAttribute(t *testing.T, set attribute.Set, key, want string) {
	t.Helper()
	value, present := set.Value(attribute.Key(key))
	if !present {
		t.Fatalf("missing attribute %s in %v", key, set)
	}
	if got := value.Emit(); got != want {
		t.Fatalf("attribute %s: got %q, want %q", key, got, want)
	}
}
