package application

import (
	"context"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
)

type EventEnvelope = kitmsg.EventEnvelope

// EventPublisher publishes fully-populated EventEnvelope values to the event bus.
type EventPublisher interface {
	Publish(ctx context.Context, envelope EventEnvelope) error
}

// EventHandler handles a deserialized EventEnvelope.
type EventHandler func(context.Context, EventEnvelope) error

// EventSubscriber subscribes to broker streams as a consumer group member.
type EventSubscriber interface {
	Subscribe(ctx context.Context, streams []string, group string, consumerName string, handler EventHandler) error
}
