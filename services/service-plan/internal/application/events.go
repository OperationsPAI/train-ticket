package application

import (
	"context"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
)

const (
	ProducerServicePlan = "service-plan"
	SchemaVersion       = 1
)

type EventEnvelope = kitmsg.EventEnvelope

type EventPublisher interface {
	Publish(ctx context.Context, envelope EventEnvelope) error
}

type EventSubscriber interface {
	Subscribe(ctx context.Context, subscription Subscription, handler EventHandler) error
}

type Subscription struct {
	Streams      []EventStream
	Group        string
	ConsumerName string
}

type EventStream struct {
	Producer string
}

type EventHandler func(context.Context, EventEnvelope) error

type HandlerErrorKind = kitmsg.HandlerErrorKind

const (
	HandlerErrorTransient = kitmsg.HandlerErrorTransient
	HandlerErrorFatal     = kitmsg.HandlerErrorFatal
)

type HandlerError = kitmsg.HandlerError

func TransientHandlerError(err error) error { return kitmsg.TransientHandlerError(err) }
func FatalHandlerError(err error) error     { return kitmsg.FatalHandlerError(err) }
func IsFatalHandlerError(err error) bool    { return kitmsg.IsFatalHandlerError(err) }

type DedupStore = kitmsg.DedupStore
type InMemoryDedupStore = kitmsg.InMemoryDedupStore

func NewInMemoryDedupStore() *InMemoryDedupStore { return kitmsg.NewInMemoryDedupStore() }
func DeduplicatingHandler(store DedupStore, next EventHandler) EventHandler {
	return func(ctx context.Context, envelope EventEnvelope) error {
		return kitmsg.DeduplicatingHandler(store, kitmsg.Handler(next))(ctx, envelope)
	}
}
