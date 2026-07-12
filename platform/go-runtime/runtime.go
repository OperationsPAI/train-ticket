// Package goruntime provides the shared production-shaped HTTP baseline for Go services.
package goruntime

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
)

const (
	RequestIDHeader     = "X-Request-ID"
	CorrelationIDHeader = "X-Correlation-ID"
)

type contextKey string

const (
	requestIDContextKey     contextKey = "go-runtime.request-id"
	correlationIDContextKey contextKey = "go-runtime.correlation-id"
)

// IDGenerator creates request identifiers when inbound requests do not provide one.
type IDGenerator func() string

// Check is used by live/ready probes. Returning an error produces a 503 response.
type Check func(context.Context) error

// Observer is an opt-in observability seam. The default NoopObserver requires no external infrastructure.
type Observer interface {
	Start(ctx context.Context, operation string) (context.Context, Span)
}

// Span is completed by middleware after request handling.
type Span interface {
	End(err error)
}

type noopObserver struct{}
type noopSpan struct{}

func (noopObserver) Start(ctx context.Context, _ string) (context.Context, Span) {
	return ctx, noopSpan{}
}
func (noopSpan) End(error) {}

// NoopObserver is the default observer for tests and services that have not opted into tracing.
func NoopObserver() Observer { return noopObserver{} }

// OTelObserverConfig configures the OpenTelemetry API adapter. It intentionally
// uses the global OpenTelemetry tracer provider so SDK/exporter setup remains an
// opt-in bootstrap concern controlled by standard OTEL_* environment variables.
type OTelObserverConfig struct {
	ServiceName string
	TracerName  string
}

type otelObserver struct {
	serviceName string
	tracer      trace.Tracer
}

type otelSpan struct{ span trace.Span }

// NewOTelObserver adapts the runtime observer seam to the OpenTelemetry API.
// Without an installed SDK provider, OpenTelemetry's global default provider is
// non-recording, preserving no-op behavior and avoiding collector dependencies
// in unit tests.
func NewOTelObserver(config OTelObserverConfig) Observer {
	tracerName := strings.TrimSpace(config.TracerName)
	if tracerName == "" {
		tracerName = "github.com/trainticket/greenfield/platform/go-runtime"
	}
	return otelObserver{serviceName: strings.TrimSpace(config.ServiceName), tracer: otel.Tracer(tracerName)}
}

// ObserverFromEnv returns an OpenTelemetry observer only when tracing is
// explicitly selected. Set OTEL_TRACES_EXPORTER to any value other than "none"
// and install an SDK/exporter in service bootstrap to emit OTLP using the
// standard OTEL_EXPORTER_OTLP_* environment contract.
func ObserverFromEnv(serviceName string) Observer {
	if !otelTracingEnabled() {
		return NoopObserver()
	}
	if strings.TrimSpace(serviceName) == "" {
		serviceName = strings.TrimSpace(env("OTEL_SERVICE_NAME"))
	}
	return NewOTelObserver(OTelObserverConfig{ServiceName: serviceName})
}

func otelTracingEnabled() bool {
	value := strings.TrimSpace(strings.ToLower(env("OTEL_TRACES_EXPORTER")))
	return value != "" && value != "none"
}

var env = os.Getenv

func (observer otelObserver) Start(ctx context.Context, operation string) (context.Context, Span) {
	attrs := []attribute.KeyValue{
		attribute.String("http.route", operation),
		attribute.String("url.path", operation),
		attribute.String("http.request_id", RequestID(ctx)),
		attribute.String("http.correlation_id", CorrelationID(ctx)),
	}
	if observer.serviceName != "" {
		attrs = append(attrs, attribute.String("service.name", observer.serviceName))
	}
	ctx, span := observer.tracer.Start(ctx, operation, trace.WithSpanKind(trace.SpanKindServer), trace.WithAttributes(attrs...))
	return ctx, otelSpan{span: span}
}

func (span otelSpan) End(err error) {
	if err != nil {
		span.span.RecordError(err)
		span.span.SetStatus(codes.Error, err.Error())
	}
	span.span.End()
}

// SetSpanHTTPAttributes enriches the active OpenTelemetry span, when one is
// recording, with the HTTP dimensions required by the runtime baseline.
func SetSpanHTTPAttributes(ctx context.Context, method, path string, statusCode int) {
	span := trace.SpanFromContext(ctx)
	if !span.IsRecording() {
		return
	}
	attrs := []attribute.KeyValue{
		attribute.String("http.request.method", method),
		attribute.String("http.method", method),
		attribute.String("url.path", path),
		attribute.String("http.route", path),
		attribute.Int("http.response.status_code", statusCode),
		attribute.Int("http.status_code", statusCode),
	}
	span.SetAttributes(attrs...)
	if statusCode >= http.StatusInternalServerError {
		span.SetStatus(codes.Error, fmt.Sprintf("http status %d", statusCode))
	}
}

// GinConfig configures a service router using the shared runtime baseline.
type GinConfig struct {
	ServiceID       string
	Metadata        any
	HealthStatus    string
	LiveCheck       Check
	ReadyCheck      Check
	Observer        Observer
	RequestIDSource IDGenerator
}

// NewGinRouter creates a gin.Engine with runtime middleware and standard operational endpoints.
func NewGinRouter(config GinConfig) *gin.Engine {
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	router.Use(RequestContextMiddleware(config.RequestIDSource))
	router.Use(TracingMiddleware(config.Observer))
	RegisterStandardEndpoints(router, config)
	return router
}

// RegisterStandardEndpoints attaches /health, /live, /livez, /ready, /readyz, and /metadata.
func RegisterStandardEndpoints(router gin.IRouter, config GinConfig) {
	healthStatus := strings.TrimSpace(config.HealthStatus)
	if healthStatus == "" {
		healthStatus = "ok"
	}
	metadata := config.Metadata

	healthHandler := func(ctx *gin.Context) {
		ctx.JSON(http.StatusOK, gin.H{"status": healthStatus, "service": metadata})
	}
	router.GET("/health", healthHandler)
	router.GET("/healthz", healthHandler)
	router.GET("/metadata", func(ctx *gin.Context) {
		ctx.JSON(http.StatusOK, metadata)
	})

	liveHandler := probeHandler("live", "alive", config.LiveCheck)
	router.GET("/live", liveHandler)
	router.GET("/livez", liveHandler)

	readyHandler := probeHandler("ready", "ready", config.ReadyCheck)
	router.GET("/ready", readyHandler)
	router.GET("/readyz", readyHandler)
}

func probeHandler(name, okStatus string, check Check) gin.HandlerFunc {
	return func(ctx *gin.Context) {
		if check != nil {
			if err := check(ctx.Request.Context()); err != nil {
				ctx.JSON(http.StatusServiceUnavailable, gin.H{"status": "unavailable", "probe": name, "error": err.Error()})
				return
			}
		}
		ctx.JSON(http.StatusOK, gin.H{"status": okStatus, "probe": name})
	}
}

// RequestContextMiddleware propagates or generates request and correlation ids.
func RequestContextMiddleware(generator IDGenerator) gin.HandlerFunc {
	if generator == nil {
		generator = GenerateRequestID
	}
	return func(ctx *gin.Context) {
		requestID := strings.TrimSpace(ctx.GetHeader(RequestIDHeader))
		if requestID == "" {
			requestID = generator()
		}
		if strings.TrimSpace(requestID) == "" {
			requestID = GenerateRequestID()
		}

		correlationID := strings.TrimSpace(ctx.GetHeader(CorrelationIDHeader))
		if correlationID == "" {
			correlationID = GenerateCorrelationID()
		}

		ctx.Writer.Header().Set(RequestIDHeader, requestID)
		ctx.Writer.Header().Set(CorrelationIDHeader, correlationID)
		ctx.Set(string(requestIDContextKey), requestID)
		ctx.Set(string(correlationIDContextKey), correlationID)

		requestContext := ContextWithRequestIDs(ctx.Request.Context(), requestID, correlationID)
		ctx.Request = ctx.Request.WithContext(requestContext)
		ctx.Next()
	}
}

// TracingMiddleware wraps every request in the configured observer span.
func TracingMiddleware(observer Observer) gin.HandlerFunc {
	if observer == nil {
		observer = NoopObserver()
	}
	return func(ctx *gin.Context) {
		operation := ctx.FullPath()
		if operation == "" {
			operation = ctx.Request.URL.Path
		}
		requestContext, span := observer.Start(ctx.Request.Context(), operation)
		ctx.Request = ctx.Request.WithContext(requestContext)
		ctx.Next()
		status := ctx.Writer.Status()
		SetSpanHTTPAttributes(ctx.Request.Context(), ctx.Request.Method, operation, status)
		var err error
		if len(ctx.Errors) > 0 {
			err = errors.New(ctx.Errors.String())
		}
		if status >= http.StatusInternalServerError && err == nil {
			err = fmt.Errorf("http status %d", status)
		}
		span.End(err)
	}
}

// ContextWithRequestIDs stores request ids in a standard context for downstream code.
func ContextWithRequestIDs(ctx context.Context, requestID, correlationID string) context.Context {
	ctx = context.WithValue(ctx, requestIDContextKey, requestID)
	ctx = context.WithValue(ctx, correlationIDContextKey, correlationID)
	return ctx
}

func RequestID(ctx context.Context) string {
	value, _ := ctx.Value(requestIDContextKey).(string)
	return value
}

func CorrelationID(ctx context.Context) string {
	value, _ := ctx.Value(correlationIDContextKey).(string)
	return value
}

// GenerateCorrelationID returns a contract-conformant correlation id (corr-<uuidv7>).
func GenerateCorrelationID() string {
	var b [16]byte
	ms := uint64(time.Now().UnixMilli())
	b[0] = byte(ms >> 40)
	b[1] = byte(ms >> 32)
	b[2] = byte(ms >> 24)
	b[3] = byte(ms >> 16)
	b[4] = byte(ms >> 8)
	b[5] = byte(ms)
	if _, err := rand.Read(b[6:]); err != nil {
		return fmt.Sprintf("corr-%d", time.Now().UnixNano())
	}
	b[6] = (b[6] & 0x0F) | 0x70
	b[8] = (b[8] & 0x3F) | 0x80
	return fmt.Sprintf("corr-%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// GenerateRequestID returns a random identifier suitable for HTTP request correlation.
func GenerateRequestID() string {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return fmt.Sprintf("req-%d", time.Now().UnixNano())
	}
	return "req-" + hex.EncodeToString(bytes[:])
}

// ServerConfig captures shared HTTP server bootstrap defaults.
type ServerConfig struct {
	Address           string
	Handler           http.Handler
	ReadHeaderTimeout time.Duration
	ReadTimeout       time.Duration
	WriteTimeout      time.Duration
	IdleTimeout       time.Duration
}

// NewHTTPServer creates a production-shaped http.Server with safe defaults.
func NewHTTPServer(config ServerConfig) *http.Server {
	address := strings.TrimSpace(config.Address)
	if address == "" {
		address = ":8080"
	}
	readHeaderTimeout := config.ReadHeaderTimeout
	if readHeaderTimeout == 0 {
		readHeaderTimeout = 5 * time.Second
	}
	readTimeout := config.ReadTimeout
	if readTimeout == 0 {
		readTimeout = 10 * time.Second
	}
	writeTimeout := config.WriteTimeout
	if writeTimeout == 0 {
		writeTimeout = 10 * time.Second
	}
	idleTimeout := config.IdleTimeout
	if idleTimeout == 0 {
		idleTimeout = 60 * time.Second
	}
	return &http.Server{
		Addr:              address,
		Handler:           config.Handler,
		ReadHeaderTimeout: readHeaderTimeout,
		ReadTimeout:       readTimeout,
		WriteTimeout:      writeTimeout,
		IdleTimeout:       idleTimeout,
	}
}

// RunHTTPServer starts the server and shuts down when ctx is cancelled.
func RunHTTPServer(ctx context.Context, server *http.Server) error {
	if server == nil {
		return errors.New("http server is required")
	}
	errCh := make(chan error, 1)
	go func() {
		err := server.ListenAndServe()
		if errors.Is(err, http.ErrServerClosed) {
			err = nil
		}
		errCh <- err
	}()

	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			return err
		}
		return <-errCh
	case err := <-errCh:
		return err
	}
}
