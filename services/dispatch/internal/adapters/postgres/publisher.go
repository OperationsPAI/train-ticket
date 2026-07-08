package postgres

import (
	"context"
	"encoding/json"
	"strings"
	"time"

	kitmessaging "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/dispatch/internal/domain"
)

type OutboxPublisher struct{ db ContextDBProvider }

func NewOutboxPublisherWithProvider(db ContextDBProvider) *OutboxPublisher {
	return &OutboxPublisher{db: db}
}
func (p *OutboxPublisher) Publish(ctx context.Context, e domain.EventEnvelope) error {
	k, err := toKitEnvelope(ctx, e)
	if err != nil {
		return err
	}
	body, err := json.Marshal(k)
	if err != nil {
		return err
	}
	return storage.NewOutboxAppender(p.db.DBFor(ctx)).Append(ctx, kitmessaging.StreamName(k.Producer), k.EventID, body)
}
func toKitEnvelope(ctx context.Context, e domain.EventEnvelope) (kitmessaging.EventEnvelope, error) {
	payload := e.Payload
	if payload == nil {
		payload = map[string]any{}
	}
	opts := kitmessaging.EnvelopeOptions{Context: ctx}
	if t, err := time.Parse(time.RFC3339Nano, e.OccurredAt); err == nil {
		opts.Now = t.UTC()
	}
	if strings.TrimSpace(e.CausationID) != "" {
		opts.CausationID = e.CausationID
	}
	k, err := kitmessaging.NewEventEnvelope(e.EventType, e.Producer, e.CorrelationID, payload, opts)
	if err != nil {
		return kitmessaging.EventEnvelope{}, err
	}
	k.EventID = e.EventID
	if strings.TrimSpace(e.Traceparent) != "" {
		k.Traceparent = e.Traceparent
		k.Tracestate = e.Tracestate
	}
	if err := k.Validate(); err != nil {
		return kitmessaging.EventEnvelope{}, err
	}
	return k, nil
}
