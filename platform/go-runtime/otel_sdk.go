package goruntime

import (
	"context"
	"fmt"
	"strings"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/sdk/resource"
	tracesdk "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

// OTelSDKConfig configures the OpenTelemetry SDK bootstrap used by Go services.
// The exporter is deliberately injectable so tests can assert span production
// without requiring a collector.
type OTelSDKConfig struct {
	ServiceName string
	Exporter    tracesdk.SpanExporter
}

// OTelShutdownFunc flushes and shuts down an installed OpenTelemetry provider.
type OTelShutdownFunc func(context.Context) error

// InitOTelSDKFromEnv installs an OpenTelemetry SDK tracer provider when tracing
// is explicitly enabled via OTEL_TRACES_EXPORTER and an OTLP endpoint is
// configured. With no OTEL_* tracing environment it returns a no-op shutdown and
// leaves the global provider alone.
func InitOTelSDKFromEnv(ctx context.Context, serviceName string) (OTelShutdownFunc, error) {
	if !otelTracingEnabled() || !otelOTLPEndpointConfigured() {
		return noopOTelShutdown, nil
	}

	exporter, err := newOTLPTraceGRPCExporter(ctx)
	if err != nil {
		return nil, err
	}
	return InitOTelSDK(OTelSDKConfig{ServiceName: serviceName, Exporter: exporter})
}

// InitOTelSDK installs an SDK tracer provider for the existing Observer seam.
// go-runtime owns HTTP/bootstrap concerns; go-kit messaging code continues to
// depend only on the Observer interface rather than on SDK/exporter details.
func InitOTelSDK(config OTelSDKConfig) (OTelShutdownFunc, error) {
	if config.Exporter == nil {
		return nil, fmt.Errorf("otel span exporter is required")
	}
	serviceName := strings.TrimSpace(config.ServiceName)
	if serviceName == "" {
		serviceName = strings.TrimSpace(env("OTEL_SERVICE_NAME"))
	}

	attrs := []attribute.KeyValue(nil)
	if serviceName != "" {
		attrs = append(attrs, attribute.String("service.name", serviceName))
	}
	provider := tracesdk.NewTracerProvider(
		tracesdk.WithSyncer(config.Exporter),
		tracesdk.WithResource(resource.NewSchemaless(attrs...)),
	)
	otel.SetTracerProvider(provider)
	return provider.Shutdown, nil
}

func newOTLPTraceGRPCExporter(ctx context.Context) (tracesdk.SpanExporter, error) {
	exportCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return otlptracegrpc.New(exportCtx)
}

func otelOTLPEndpointConfigured() bool {
	return strings.TrimSpace(env("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")) != "" || strings.TrimSpace(env("OTEL_EXPORTER_OTLP_ENDPOINT")) != ""
}

func noopOTelShutdown(context.Context) error { return nil }

// ResetOTelForTest restores OpenTelemetry's no-op global provider. It is
// exported only for sibling platform modules that need isolated SDK tests.
func ResetOTelForTest() {
	otel.SetTracerProvider(trace.NewNoopTracerProvider())
}
