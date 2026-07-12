package http

import (
	"context"

	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
)

type NoopPublisher struct{}

func (NoopPublisher) Publish(context.Context, application.EventEnvelope) error { return nil }
