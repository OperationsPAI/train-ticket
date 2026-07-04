// Package goruntime provides the shared production-shaped HTTP baseline for Go services.
package goruntime

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
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

	router.GET("/health", func(ctx *gin.Context) {
		ctx.JSON(http.StatusOK, gin.H{"status": healthStatus, "service": metadata})
	})
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
			correlationID = requestID
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
		var err error
		if len(ctx.Errors) > 0 {
			err = errors.New(ctx.Errors.String())
		}
		if ctx.Writer.Status() >= http.StatusInternalServerError && err == nil {
			err = fmt.Errorf("http status %d", ctx.Writer.Status())
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
