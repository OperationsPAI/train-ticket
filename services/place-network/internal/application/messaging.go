package application

import (
	"context"
	"sync"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

type EventPublisher interface {
	Publish(ctx context.Context, envelope domain.EventEnvelope) error
}

type HandlerResult int

const (
	HandlerSuccess HandlerResult = iota
	HandlerTransientError
	HandlerFatalError
)

type EventHandler func(envelope domain.EventEnvelope) HandlerResult

type EventSubscriber interface {
	Subscribe(streams []string, group string, consumerName string, handler EventHandler) error
}

type DeduplicatingEventHandler struct {
	mu      sync.Mutex
	seen    map[string]struct{}
	handler EventHandler
}

func NewDeduplicatingEventHandler(handler EventHandler) *DeduplicatingEventHandler {
	return &DeduplicatingEventHandler{seen: make(map[string]struct{}), handler: handler}
}

func (h *DeduplicatingEventHandler) Handle(envelope domain.EventEnvelope) HandlerResult {
	h.mu.Lock()
	if _, exists := h.seen[envelope.EventID]; exists {
		h.mu.Unlock()
		return HandlerSuccess
	}
	h.mu.Unlock()

	result := h.handler(envelope)
	if result == HandlerSuccess {
		h.mu.Lock()
		h.seen[envelope.EventID] = struct{}{}
		h.mu.Unlock()
	}
	return result
}

type NoopPublisher struct{}

func NewNoopPublisher() *NoopPublisher { return &NoopPublisher{} }

func (p *NoopPublisher) Publish(ctx context.Context, envelope domain.EventEnvelope) error { return nil }
