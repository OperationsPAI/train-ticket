package messaging

import (
	"context"
	"strings"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

type contextKey string

const (
	streamContextKey        contextKey = "go-kit.messaging.stream"
	consumerGroupContextKey contextKey = "go-kit.messaging.consumer-group"
)

// Observer is the go-runtime observability seam reused by go-kit adapters.
type Observer = goruntime.Observer

// NoopObserver returns an observer with zero side effects.
func NoopObserver() Observer { return goruntime.NoopObserver() }

// ObserverFromEnv returns an OpenTelemetry observer only when OTEL tracing is enabled.
func ObserverFromEnv(serviceName string) Observer { return goruntime.ObserverFromEnv(serviceName) }

// MessageContext stores stream and consumer-group attributes for downstream code.
func MessageContext(ctx context.Context, stream, consumerGroup string) context.Context {
	ctx = context.WithValue(ctx, streamContextKey, strings.TrimSpace(stream))
	ctx = context.WithValue(ctx, consumerGroupContextKey, strings.TrimSpace(consumerGroup))
	return ctx
}

// StreamFromContext returns the Redis stream attached by the subscriber.
func StreamFromContext(ctx context.Context) string {
	value, _ := ctx.Value(streamContextKey).(string)
	return value
}

// ConsumerGroupFromContext returns the consumer group attached by the subscriber.
func ConsumerGroupFromContext(ctx context.Context) string {
	value, _ := ctx.Value(consumerGroupContextKey).(string)
	return value
}

// ObservedHandler wraps event handling in a consumer span. It does not mutate
// the event envelope; traceparent propagation is intentionally out-of-band.
func ObservedHandler(observer Observer, stream, consumerGroup string, next Handler) Handler {
	if observer == nil {
		observer = NoopObserver()
	}
	return func(ctx context.Context, envelope EventEnvelope) error {
		ctx = MessageContext(ctx, stream, consumerGroup)
		operation := strings.TrimSpace(envelope.EventType)
		if operation == "" {
			operation = "messaging.consume"
		}
		ctx, span := observer.Start(ctx, operation)
		SetSpanMessagingAttributes(ctx, stream, consumerGroup, envelope)
		err := next(ctx, envelope)
		span.End(err)
		return err
	}
}

// SetSpanMessagingAttributes enriches the active span with REQ-096 messaging dimensions.
func SetSpanMessagingAttributes(ctx context.Context, stream, consumerGroup string, envelope EventEnvelope) {
	span := trace.SpanFromContext(ctx)
	if !span.IsRecording() {
		return
	}
	span.SetAttributes(
		attribute.String("stream", strings.TrimSpace(stream)),
		attribute.String("consumerGroup", strings.TrimSpace(consumerGroup)),
		attribute.String("eventId", strings.TrimSpace(envelope.EventID)),
		attribute.String("eventType", strings.TrimSpace(envelope.EventType)),
		attribute.String("correlationId", strings.TrimSpace(envelope.CorrelationID)),
	)
}
