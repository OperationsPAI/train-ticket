package messaging

import (
	"context"
	"errors"
	"sync"
)

type Handler func(context.Context, EventEnvelope) error

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
	var h HandlerError
	return errors.As(err, &h) && h.Kind == HandlerErrorFatal
}

// IsTransientHandlerError reports whether a handler explicitly declared its
// error retryable.
//
// The distinction matters because a bare error is not a classification: a
// handler that returns one has told the subscriber only that something went
// wrong. Wrapping with TransientHandlerError is the Go counterpart of Java's
// HandlerResult.TRANSIENT_FAILURE, and only that wrapper is reported as
// transient_result.
func IsTransientHandlerError(err error) bool {
	var h HandlerError
	return errors.As(err, &h) && h.Kind == HandlerErrorTransient
}

type DedupStore interface {
	Seen(eventID string) bool
	Record(eventID string)
}
type InMemoryDedupStore struct {
	mu   sync.Mutex
	seen map[string]struct{}
}

func NewInMemoryDedupStore() *InMemoryDedupStore {
	return &InMemoryDedupStore{seen: map[string]struct{}{}}
}
func (s *InMemoryDedupStore) Seen(eventID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, ok := s.seen[eventID]
	return ok
}
func (s *InMemoryDedupStore) Record(eventID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.seen[eventID] = struct{}{}
}
func DeduplicatingHandler(store DedupStore, next Handler) Handler {
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
