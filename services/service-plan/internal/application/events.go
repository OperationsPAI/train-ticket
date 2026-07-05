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

const (
	ProducerServicePlan = "service-plan"
	SchemaVersion       = 1
)

type EventEnvelope struct {
	EventID       string          `json:"eventId"`
	EventType     string          `json:"eventType"`
	OccurredAt    time.Time       `json:"occurredAt"`
	CorrelationID string          `json:"correlationId"`
	CausationID   string          `json:"causationId,omitempty"`
	Producer      string          `json:"producer"`
	SchemaVersion int             `json:"schemaVersion"`
	Payload       json.RawMessage `json:"payload"`
}

func (e EventEnvelope) Validate() error {
	if !validPrefixedUUIDv7(e.EventID, "evt") {
		return fmt.Errorf("eventId must be evt-prefixed UUID")
	}
	if strings.TrimSpace(e.EventType) == "" {
		return fmt.Errorf("eventType is required")
	}
	if e.OccurredAt.IsZero() {
		return fmt.Errorf("occurredAt is required")
	}
	if !validPrefixedUUIDv7(e.CorrelationID, "corr") {
		return fmt.Errorf("correlationId must be corr-prefixed UUID")
	}
	if e.CausationID != "" && !validPrefixedUUIDv7(e.CausationID, "cmd") && !validPrefixedUUIDv7(e.CausationID, "evt") {
		return fmt.Errorf("causationId must be cmd- or evt-prefixed UUID")
	}
	if strings.TrimSpace(e.Producer) == "" {
		return fmt.Errorf("producer is required")
	}
	if e.SchemaVersion != SchemaVersion {
		return fmt.Errorf("unsupported schemaVersion: %d", e.SchemaVersion)
	}
	if len(e.Payload) == 0 || !json.Valid(e.Payload) {
		return fmt.Errorf("payload must be valid json")
	}
	return nil
}

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

func (e HandlerError) Unwrap() error { return e.Err }

func TransientHandlerError(err error) error {
	if err == nil {
		err = errors.New("transient handler error")
	}
	return HandlerError{Kind: HandlerErrorTransient, Err: err}
}

func FatalHandlerError(err error) error {
	if err == nil {
		err = errors.New("fatal handler error")
	}
	return HandlerError{Kind: HandlerErrorFatal, Err: err}
}

func IsFatalHandlerError(err error) bool {
	var handlerErr HandlerError
	return errors.As(err, &handlerErr) && handlerErr.Kind == HandlerErrorFatal
}

type DedupStore interface {
	Seen(eventID string) bool
	Record(eventID string)
}

type InMemoryDedupStore struct {
	mu      sync.Mutex
	eventID map[string]struct{}
}

func NewInMemoryDedupStore() *InMemoryDedupStore {
	return &InMemoryDedupStore{eventID: map[string]struct{}{}}
}

func (s *InMemoryDedupStore) Seen(eventID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, exists := s.eventID[eventID]
	return exists
}

func (s *InMemoryDedupStore) Record(eventID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.eventID[eventID] = struct{}{}
}

func DeduplicatingHandler(store DedupStore, next EventHandler) EventHandler {
	return func(ctx context.Context, envelope EventEnvelope) error {
		if store.Seen(envelope.EventID) {
			return nil
		}
		if err := next(ctx, envelope); err != nil {
			return err
		}
		store.Record(envelope.EventID)
		return nil
	}
}
