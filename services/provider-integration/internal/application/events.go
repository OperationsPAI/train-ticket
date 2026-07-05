package application

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
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
	CausationID   string          `json:"causationId"`
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
		EventID:       newPrefixedID("evt"),
		EventType:     strings.TrimSpace(eventType),
		OccurredAt:    now.Format(time.RFC3339Nano),
		CorrelationID: canonicalCorrelationID(correlationID),
		CausationID:   canonicalCausationID(causationID),
		Producer:      ProducerName,
		SchemaVersion: 1,
		Payload:       payloadBytes,
	}, nil
}

func canonicalCorrelationID(value string) string {
	trimmed := strings.TrimSpace(value)
	if hasPrefixedUUID(trimmed, "corr") {
		return trimmed
	}
	if id, err := uuid.Parse(trimmed); err == nil {
		return "corr-" + id.String()
	}
	return newPrefixedID("corr")
}

func canonicalCausationID(value string) string {
	trimmed := strings.TrimSpace(value)
	if hasPrefixedUUID(trimmed, "cmd") || hasPrefixedUUID(trimmed, "evt") {
		return trimmed
	}
	return newPrefixedID("cmd")
}

func hasPrefixedUUID(value, prefix string) bool {
	id, err := uuid.Parse(strings.TrimPrefix(value, prefix+"-"))
	return err == nil && strings.HasPrefix(value, prefix+"-") && id != uuid.Nil
}

func newPrefixedID(prefix string) string {
	id, err := uuid.NewV7()
	if err != nil {
		id = uuid.New()
	}
	return fmt.Sprintf("%s-%s", prefix, id.String())
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
