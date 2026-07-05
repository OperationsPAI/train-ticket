package application

import (
	"context"
	"time"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
)

// EventEnvelope is the broker-neutral wire envelope required by the shared
// messaging contract. It intentionally contains exactly the eight contract
// fields and uses the same camelCase JSON names as the on-wire message.
type EventEnvelope struct {
	EventID       string      `json:"eventId"`
	EventType     string      `json:"eventType"`
	OccurredAt    time.Time   `json:"occurredAt"`
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

type HandlerErrorKind = kitmsg.HandlerErrorKind

const (
	HandlerErrorTransient = kitmsg.HandlerErrorTransient
	HandlerErrorFatal     = kitmsg.HandlerErrorFatal
)

type HandlerError = kitmsg.HandlerError

// EventHandler handles a deserialized EventEnvelope.
type EventHandler func(context.Context, EventEnvelope) error

// EventSubscriber subscribes to broker streams as a consumer group member.
type EventSubscriber interface {
	Subscribe(ctx context.Context, streams []string, group string, consumerName string, handler EventHandler) error
}
