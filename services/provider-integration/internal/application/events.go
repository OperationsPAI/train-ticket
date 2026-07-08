package application

import (
	"context"
	"errors"
	"strings"
	"sync"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
)

const ProducerName = "provider-integration"

var (
	ErrPublishFailed   = errors.New("publish failed")
	ErrSubscribeFailed = errors.New("subscribe failed")
)

type HandlerErrorKind = kitmsg.HandlerErrorKind

const (
	HandlerErrorTransient = kitmsg.HandlerErrorTransient
	HandlerErrorFatal     = kitmsg.HandlerErrorFatal
)

type HandlerError = kitmsg.HandlerError

type EventEnvelope = kitmsg.EventEnvelope

type EventPublisher interface {
	Publish(ctx context.Context, envelope EventEnvelope) error
}

type EventHandler func(context.Context, EventEnvelope) error

type EventSubscriber interface {
	Subscribe(ctx context.Context, streams []string, group string, consumerName string, handler EventHandler) error
}

func TransientHandlerError(err error) error { return kitmsg.TransientHandlerError(err) }
func FatalHandlerError(err error) error     { return kitmsg.FatalHandlerError(err) }

func NewEventEnvelope(ctx context.Context, eventType, correlationID, causationID string, payload any) (EventEnvelope, error) {
	option := kitmsg.EnvelopeOptions{Context: ctx}
	if strings.TrimSpace(causationID) != "" {
		option.CausationID = causationID
	}
	return kitmsg.NewEventEnvelope(eventType, ProducerName, correlationID, payload, option)
}

func canonicalCorrelationID(value string) string { return ids.CanonicalCorrelationID(value) }
func hasPrefixedUUID(value, prefix string) bool  { return ids.ValidPrefixedUUIDv7(value, prefix) }
func newPrefixedID(prefix string) string         { return ids.NewPrefixed(prefix) }

type ConsumedEventLog interface {
	AlreadyProcessed(eventID string) bool
	RecordProcessed(eventID string)
}

type InMemoryConsumedEventLog struct {
	mu       sync.Mutex
	consumed map[string]struct{}
}

func NewInMemoryConsumedEventLog() *InMemoryConsumedEventLog {
	return &InMemoryConsumedEventLog{consumed: map[string]struct{}{}}
}

func (l *InMemoryConsumedEventLog) AlreadyProcessed(eventID string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	_, ok := l.consumed[eventID]
	return ok
}

func (l *InMemoryConsumedEventLog) RecordProcessed(eventID string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.consumed[eventID] = struct{}{}
}

func DeduplicatingHandler(log ConsumedEventLog, next EventHandler) EventHandler {
	return func(ctx context.Context, envelope EventEnvelope) error {
		if log != nil && log.AlreadyProcessed(envelope.EventID) {
			return nil
		}
		if err := next(ctx, envelope); err != nil {
			return err
		}
		if log != nil {
			log.RecordProcessed(envelope.EventID)
		}
		return nil
	}
}
