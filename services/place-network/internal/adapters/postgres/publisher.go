package postgres

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	kitmessaging "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

type ContextDBProvider interface {
	DBFor(context.Context) storage.DBTX
}

type OutboxPublisher struct{ db ContextDBProvider }

func NewOutboxPublisher(db storage.DBTX) *OutboxPublisher {
	return NewOutboxPublisherWithProvider(staticDB{db: db})
}
func NewOutboxPublisherWithProvider(db ContextDBProvider) *OutboxPublisher {
	return &OutboxPublisher{db: db}
}

func (p *OutboxPublisher) Publish(ctx context.Context, envelope domain.EventEnvelope) error {
	kitEnvelope, err := toKitEnvelope(ctx, envelope)
	if err != nil {
		return err
	}
	body, err := json.Marshal(kitEnvelope)
	if err != nil {
		return fmt.Errorf("marshal outbox envelope: %w", err)
	}
	return storage.NewOutboxAppender(p.db.DBFor(ctx)).Append(ctx, kitmessaging.StreamName(kitEnvelope.Producer), kitEnvelope.EventID, body)
}

func toKitEnvelope(ctx context.Context, envelope domain.EventEnvelope) (kitmessaging.EventEnvelope, error) {
	payload := envelope.Payload
	if payload == nil {
		payload = map[string]any{}
	}
	options := kitmessaging.EnvelopeOptions{Context: ctx}
	if occurredAt, err := time.Parse(time.RFC3339Nano, envelope.OccurredAt); err == nil {
		options.Now = occurredAt.UTC()
	}
	if strings.TrimSpace(envelope.CausationID) != "" {
		options.CausationID = envelope.CausationID
	}
	kitEnvelope, err := kitmessaging.NewEventEnvelope(envelope.EventType, envelope.Producer, envelope.CorrelationID, payload, options)
	if err != nil {
		return kitmessaging.EventEnvelope{}, err
	}
	kitEnvelope.EventID = envelope.EventID
	if strings.TrimSpace(envelope.Traceparent) != "" {
		kitEnvelope.Traceparent = envelope.Traceparent
		kitEnvelope.Tracestate = envelope.Tracestate
	}
	return kitEnvelope, nil
}
