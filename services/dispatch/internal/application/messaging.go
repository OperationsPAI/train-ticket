package application

import (
	"context"
	"github.com/trainticket/greenfield/services/dispatch/internal/domain"
)

type EventPublisher interface {
	Publish(context.Context, domain.EventEnvelope) error
}
type NoopPublisher struct{}

func NewNoopPublisher() *NoopPublisher                                    { return &NoopPublisher{} }
func (NoopPublisher) Publish(context.Context, domain.EventEnvelope) error { return nil }
