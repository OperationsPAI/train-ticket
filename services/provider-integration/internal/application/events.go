package application

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
)

const ProducerName = "provider-integration"

var (
	ErrPublishFailed   = errors.New("publish failed")
	ErrSubscribeFailed = errors.New("subscribe failed")
)

type HandlerErrorKind string

const (
	HandlerErrorTransient HandlerErrorKind = "TRANSIENT"
	HandlerErrorFatal     HandlerErrorKind = "FATAL"
)

type HandlerError struct {
	Kind HandlerErrorKind
	Err  error
}

func (e HandlerError) Error() string {
	if e.Err == nil {
		return string(e.Kind)
	}
	return e.Err.Error()
}

func TransientHandlerError(err error) HandlerError {
	return HandlerError{Kind: HandlerErrorTransient, Err: err}
}
func FatalHandlerError(err error) HandlerError {
	return HandlerError{Kind: HandlerErrorFatal, Err: err}
}

type EventEnvelope struct {
	EventID       string          `json:"eventId"`
	EventType     string          `json:"eventType"`
	OccurredAt    string          `json:"occurredAt"`
	CorrelationID string          `json:"correlationId"`
	CausationID   string          `json:"causationId,omitempty"`
	Producer      string          `json:"producer"`
	SchemaVersion int             `json:"schemaVersion"`
	Payload       json.RawMessage `json:"payload"`
}

type EventPublisher interface {
	Publish(ctx context.Context, envelope EventEnvelope) error
}

type EventHandler func(context.Context, EventEnvelope) error

type EventSubscriber interface {
	Subscribe(ctx context.Context, streams []string, group string, consumerName string, handler EventHandler) error
}

func NewEventEnvelope(eventType, correlationID, causationID string, payload any) (EventEnvelope, error) {
	payloadBytes, err := json.Marshal(payload)
	if err != nil {
		return EventEnvelope{}, fmt.Errorf("marshal event payload: %w", err)
	}
	now := time.Now().UTC()
	return EventEnvelope{
		EventID:       newPrefixedID("evt", now),
		EventType:     strings.TrimSpace(eventType),
		OccurredAt:    now.Format(time.RFC3339Nano),
		CorrelationID: strings.TrimSpace(correlationID),
		CausationID:   strings.TrimSpace(causationID),
		Producer:      ProducerName,
		SchemaVersion: 1,
		Payload:       payloadBytes,
	}, nil
}

func newPrefixedID(prefix string, now time.Time) string {
	return fmt.Sprintf("%s-%d", prefix, now.UnixNano())
}

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
