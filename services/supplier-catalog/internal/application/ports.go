package application

import "context"

// EventEnvelope is the broker-neutral wire envelope required by the shared
// messaging contract. It intentionally contains exactly the eight contract
// fields and uses the same camelCase JSON names as the on-wire message.
type EventEnvelope struct {
	EventID       string      `json:"eventId"`
	EventType     string      `json:"eventType"`
	OccurredAt    string      `json:"occurredAt"`
	CorrelationID string      `json:"correlationId"`
	CausationID   string      `json:"causationId"`
	Producer      string      `json:"producer"`
	SchemaVersion int         `json:"schemaVersion"`
	Payload       interface{} `json:"payload"`
}

// EventPublisher publishes fully-populated EventEnvelope values to the event bus.
type EventPublisher interface {
	Publish(ctx context.Context, envelope EventEnvelope) error
}

// HandlerErrorKind describes whether a subscriber handler failure should be
// retried or moved to the dead-letter stream.
type HandlerErrorKind int

const (
	HandlerErrorTransient HandlerErrorKind = iota
	HandlerErrorFatal
)

// HandlerError is returned by event handlers to guide subscriber ack/DLQ behavior.
type HandlerError struct {
	Kind HandlerErrorKind
	Err  error
}

func (e HandlerError) Error() string {
	if e.Err == nil {
		return "event handler failed"
	}
	return e.Err.Error()
}

// EventHandler handles a deserialized EventEnvelope.
type EventHandler func(context.Context, EventEnvelope) error

// EventSubscriber subscribes to broker streams as a consumer group member.
type EventSubscriber interface {
	Subscribe(ctx context.Context, streams []string, group string, consumerName string, handler EventHandler) error
}
