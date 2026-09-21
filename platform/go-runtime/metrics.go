package goruntime

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/semconv/v1.37.0/httpconv"
)

// HTTPServerMetrics records the HTTP server instruments the semantic
// conventions define. The instrument constructors come from httpconv, so the
// names, units and required attributes are the convention's rather than this
// repository's.
//
// There is no request counter: the duration histogram carries its own count, so
// a second instrument would be a parallel series with the same information.
type HTTPServerMetrics struct {
	requestDuration httpconv.ServerRequestDuration
	activeRequests  httpconv.ServerActiveRequests
}

// NewHTTPServerMetrics builds the instruments on meter. A nil meter yields no-op
// instruments, which is what httpconv returns for one, so the middleware is safe
// to install unconditionally.
func NewHTTPServerMetrics(meter metric.Meter) (*HTTPServerMetrics, error) {
	requestDuration, err := httpconv.NewServerRequestDuration(meter)
	if err != nil {
		return nil, err
	}
	activeRequests, err := httpconv.NewServerActiveRequests(meter)
	if err != nil {
		return nil, err
	}
	return &HTTPServerMetrics{requestDuration: requestDuration, activeRequests: activeRequests}, nil
}

// HTTPMetricsFromEnv builds the HTTP server instruments only when metrics export
// is selected, mirroring ObserverFromEnv. It returns nil otherwise, which
// MetricsMiddleware handles as a pass-through.
//
// The nil is deliberate rather than an always-on instrument set against the
// global no-op provider: recording into no-op instruments still costs the
// attribute allocation on every request, and a service with metrics disabled
// must pay nothing.
func HTTPMetricsFromEnv(serviceName string) (*HTTPServerMetrics, error) {
	if !otelMetricsEnabled() {
		return nil, nil
	}
	return NewHTTPServerMetrics(MeterFromEnv(serviceName))
}

// MetricsMiddleware records duration and in-flight count for every request.
//
// A nil receiver is a no-op handler so the router can install this without
// knowing whether metrics are enabled.
func (metrics *HTTPServerMetrics) MetricsMiddleware() gin.HandlerFunc {
	if metrics == nil {
		return func(ctx *gin.Context) { ctx.Next() }
	}
	return func(ctx *gin.Context) {
		method := requestMethod(ctx.Request.Method)
		scheme := requestScheme(ctx.Request)
		metrics.activeRequests.Add(ctx.Request.Context(), 1, method, scheme)
		// Monotonic clock: a duration is reported in seconds and the wall clock
		// can step backwards, which would record a negative sample.
		startedAt := time.Now()

		ctx.Next()

		metrics.activeRequests.Add(ctx.Request.Context(), -1, method, scheme)
		status := ctx.Writer.Status()
		metrics.requestDuration.Record(
			ctx.Request.Context(),
			time.Since(startedAt).Seconds(),
			method,
			scheme,
			completedRequestAttributes(ctx, status)...,
		)
	}
}

// completedRequestAttributes returns the optional attributes the convention
// allows beside the required method and scheme.
//
// http.route is the matched gin route rather than the raw path. A path holds
// ids, so recording it would produce one series per order id; gin leaves
// FullPath empty for an unmatched request, and the convention permits omitting
// the attribute in exactly that case. Omission is required here rather than
// merely allowed: an unrouted path is chosen by the caller, so anyone reaching
// the service could otherwise create unbounded series.
func completedRequestAttributes(ctx *gin.Context, status int) []attribute.KeyValue {
	attrs := []attribute.KeyValue{
		attribute.Int("http.response.status_code", status),
	}
	if route := ctx.FullPath(); route != "" {
		attrs = append(attrs, attribute.String("http.route", route))
	}
	// error.type is what makes an error rate readable off the histogram. gin
	// collects handler errors in ctx.Errors; a 5xx without one still counts,
	// reported as the status code, which the convention permits.
	if len(ctx.Errors) > 0 {
		attrs = append(attrs, attribute.String("error.type", ctx.Errors[0].Error()))
	} else if status >= http.StatusInternalServerError {
		attrs = append(attrs, attribute.String("error.type", http.StatusText(status)))
	}
	return attrs
}

// requestMethod maps a method onto the convention's enumeration. An unregistered
// method becomes _OTHER: a client can send any token there, and a series per
// invented method name is a cardinality leak reachable from outside.
func requestMethod(method string) httpconv.RequestMethodAttr {
	switch method {
	case http.MethodGet:
		return httpconv.RequestMethodGet
	case http.MethodHead:
		return httpconv.RequestMethodHead
	case http.MethodPost:
		return httpconv.RequestMethodPost
	case http.MethodPut:
		return httpconv.RequestMethodPut
	case http.MethodPatch:
		return httpconv.RequestMethodPatch
	case http.MethodDelete:
		return httpconv.RequestMethodDelete
	case http.MethodConnect:
		return httpconv.RequestMethodConnect
	case http.MethodOptions:
		return httpconv.RequestMethodOptions
	case http.MethodTrace:
		return httpconv.RequestMethodTrace
	default:
		return httpconv.RequestMethodOther
	}
}

func requestScheme(request *http.Request) string {
	if request.TLS != nil {
		return "https"
	}
	if request.URL != nil && request.URL.Scheme != "" {
		return request.URL.Scheme
	}
	return "http"
}
