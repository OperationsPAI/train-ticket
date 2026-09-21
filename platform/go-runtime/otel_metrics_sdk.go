package goruntime

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	otelruntime "go.opentelemetry.io/contrib/instrumentation/runtime"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetricgrpc"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/metric/noop"
	metricsdk "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
)

// defaultMetricExportInterval matches the OpenTelemetry SDK specification's
// default for the periodic reader.
const defaultMetricExportInterval = 60 * time.Second

// OTelMetricsConfig configures the metrics half of the SDK bootstrap. The
// exporter is injectable for the same reason the span exporter is: a test has to
// be able to read what was produced without a collector.
type OTelMetricsConfig struct {
	ServiceName string
	Exporter    metricsdk.Exporter
	// Reader replaces the periodic reader built from Exporter. A test supplies a
	// manual reader so collection happens when it asks rather than on a timer.
	// Exactly one of Exporter and Reader is required.
	Reader metricsdk.Reader
	// Interval overrides the periodic reader's period. Zero takes
	// OTEL_METRIC_EXPORT_INTERVAL, then the specification default. Ignored when
	// Reader is set.
	Interval time.Duration
}

// InitOTelMetricsFromEnv installs an SDK meter provider and starts Go runtime
// instrumentation when metrics export is selected through
// OTEL_METRICS_EXPORTER and an OTLP endpoint is configured. Without that
// environment it returns a no-op shutdown and leaves the global provider alone,
// so a service run without a collector produces no network traffic.
func InitOTelMetricsFromEnv(ctx context.Context, serviceName string) (OTelShutdownFunc, error) {
	if !otelMetricsEnabled() || !otelOTLPEndpointConfigured() {
		return noopOTelShutdown, nil
	}

	exporter, err := newOTLPMetricGRPCExporter(ctx)
	if err != nil {
		return nil, err
	}
	return InitOTelMetrics(OTelMetricsConfig{ServiceName: serviceName, Exporter: exporter})
}

// InitTelemetryFromEnv installs both signals and returns one shutdown covering
// them, so a service's bootstrap has a single telemetry line rather than one per
// signal. Each half is independently gated by its own OTEL_*_EXPORTER variable.
func InitTelemetryFromEnv(ctx context.Context, serviceName string) (OTelShutdownFunc, error) {
	shutdownTraces, err := InitOTelSDKFromEnv(ctx, serviceName)
	if err != nil {
		return nil, err
	}
	shutdownMetrics, err := InitOTelMetricsFromEnv(ctx, serviceName)
	if err != nil {
		// The tracer provider is already installed, so it is torn down rather than
		// left exporting from a process that failed to finish starting.
		_ = shutdownTraces(ctx)
		return nil, err
	}
	return func(shutdownCtx context.Context) error {
		// Both run even when the first fails: a metric provider left running holds
		// a gRPC connection and a collection goroutine.
		traceErr := shutdownTraces(shutdownCtx)
		metricErr := shutdownMetrics(shutdownCtx)
		return errors.Join(traceErr, metricErr)
	}, nil
}

// InitOTelMetrics installs the meter provider and binds the runtime
// instrumentation to it.
//
// The runtime metrics come from contrib's own instrumentation rather than from
// hand-written gauges: it publishes the names in the Go semantic conventions
// (go.memory.used, go.goroutine.count and the rest), which is what a reader
// querying a Go service expects to find.
func InitOTelMetrics(config OTelMetricsConfig) (OTelShutdownFunc, error) {
	reader := config.Reader
	if reader == nil {
		if config.Exporter == nil {
			return nil, fmt.Errorf("otel metric exporter or reader is required")
		}
		interval := config.Interval
		if interval <= 0 {
			interval = metricExportInterval()
		}
		reader = metricsdk.NewPeriodicReader(config.Exporter, metricsdk.WithInterval(interval))
	}
	serviceName := strings.TrimSpace(config.ServiceName)
	if serviceName == "" {
		serviceName = strings.TrimSpace(env("OTEL_SERVICE_NAME"))
	}

	attrs := []attribute.KeyValue(nil)
	if serviceName != "" {
		attrs = append(attrs, attribute.String("service.name", serviceName))
	}
	provider := metricsdk.NewMeterProvider(
		metricsdk.WithReader(reader),
		metricsdk.WithResource(resource.NewSchemaless(attrs...)),
	)
	otel.SetMeterProvider(provider)

	if err := otelruntime.Start(otelruntime.WithMeterProvider(provider)); err != nil {
		// The provider is already global at this point, so it has to be torn down
		// rather than left publishing a partial instrument set.
		_ = provider.Shutdown(context.Background())
		otel.SetMeterProvider(noop.NewMeterProvider())
		return nil, fmt.Errorf("start go runtime metrics: %w", err)
	}
	return provider.Shutdown, nil
}

func newOTLPMetricGRPCExporter(ctx context.Context) (metricsdk.Exporter, error) {
	exportCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return otlpmetricgrpc.New(exportCtx)
}

// MeterFromEnv returns the meter the kit's own instrumentation records through.
// Without an installed SDK provider the global default is a no-op, so callers
// need no condition of their own.
func MeterFromEnv(serviceName string) metric.Meter {
	name := strings.TrimSpace(serviceName)
	if name == "" {
		name = strings.TrimSpace(env("OTEL_SERVICE_NAME"))
	}
	return otel.Meter("github.com/trainticket/greenfield/platform/go-runtime",
		metric.WithInstrumentationAttributes(attribute.String("service.name", name)))
}

func otelMetricsEnabled() bool {
	value := strings.TrimSpace(strings.ToLower(env("OTEL_METRICS_EXPORTER")))
	return value != "" && value != "none"
}

// metricExportInterval reads OTEL_METRIC_EXPORT_INTERVAL, the standard SDK
// variable, which is specified in milliseconds. An unparseable or non-positive
// value falls back to the specification default rather than failing startup:
// this governs how often metrics are sent, and a service must still start.
func metricExportInterval() time.Duration {
	value := strings.TrimSpace(env("OTEL_METRIC_EXPORT_INTERVAL"))
	if value == "" {
		return defaultMetricExportInterval
	}
	millis, err := strconv.Atoi(value)
	if err != nil || millis <= 0 {
		return defaultMetricExportInterval
	}
	return time.Duration(millis) * time.Millisecond
}

// ResetOTelMetricsForTest restores OpenTelemetry's no-op global meter provider.
// Exported for the same reason ResetOTelForTest is.
func ResetOTelMetricsForTest() {
	otel.SetMeterProvider(noop.NewMeterProvider())
}
