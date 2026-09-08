package goruntime

import (
	"context"

	"go.opentelemetry.io/otel/trace"
)

// Trace-to-log correlation for Go services.
//
// WHY THIS EXISTS
// ---------------
// The platform had full W3C trace propagation (traceparent over HTTP, trace
// context on every event envelope) and structured logs, and no way to get from
// one to the other: nothing put a trace id where the log line could pick it up.
// Diagnosing the zero-refund chain meant matching wall-clock timestamps by hand
// across five `kubectl logs` invocations for five different services.
//
// WHY THIS IS A HELPER AND NOT A HANDLER
// --------------------------------------
// Go has no ambient context: the span rides on the request's context.Context, so
// the ids must be read per call site rather than bound to a scope the way
// java-kit binds them to the SLF4J MDC. `log/slog` would let a Handler read the
// span out of the context and add the attributes once -- but no service in this
// repository uses slog; they all use the standard library's `log` package, whose
// Printf has no context parameter at all. Converting them would mean rewriting
// every call site, so instead the fields are rendered here and interpolated at
// the call sites that already have a context in hand.
//
// FIELD NAMES
// trace_id / span_id are the OpenTelemetry logging convention and match the keys
// java-kit binds into the MDC and the fields rust-kit, ts-kit and python-kit
// render, so one query joins log lines across all five languages.
const (
	TraceIDField = "trace_id"
	SpanIDField  = "span_id"
)

// TraceIDs returns the trace and span ids carried by ctx.
//
// ok is false when there is no span, when the span context is not valid, and for
// a nil context. An absent or non-recording span yields an all-zero trace id;
// reporting that is worse than reporting nothing, because it looks like a real
// id and joins every unrelated line in the log together.
func TraceIDs(ctx context.Context) (traceID string, spanID string, ok bool) {
	if ctx == nil {
		return "", "", false
	}
	spanContext := trace.SpanContextFromContext(ctx)
	if !spanContext.IsValid() {
		return "", "", false
	}
	return spanContext.TraceID().String(), spanContext.SpanID().String(), true
}

// TraceLogFields renders ctx's ids for a log line, or "" when ctx carries no
// valid span.
//
// The rendering carries its own trailing separator and is empty when there is no
// span, so a call site can interpolate it unconditionally: lines logged outside
// any span (startup, shutdown, background timers) read exactly as they did
// before rather than showing an empty placeholder.
func TraceLogFields(ctx context.Context) string {
	traceID, spanID, ok := TraceIDs(ctx)
	if !ok {
		return ""
	}
	return TraceIDField + "=" + traceID + " " + SpanIDField + "=" + spanID + " "
}
