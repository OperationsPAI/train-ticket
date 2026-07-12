package application

import "context"

type NoopPublisher struct{}

func (NoopPublisher) Publish(context.Context, EventEnvelope) error { return nil }
