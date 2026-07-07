package postgres

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/supplier-catalog/internal/application"
)

type OutboxPublisher struct{ db ContextDBProvider }

func NewOutboxPublisher(db storage.DBTX) *OutboxPublisher {
	return &OutboxPublisher{db: staticDB{db: db}}
}
func NewOutboxPublisherWithProvider(db ContextDBProvider) *OutboxPublisher {
	return &OutboxPublisher{db: db}
}
func (p *OutboxPublisher) Publish(ctx context.Context, envelope application.EventEnvelope) error {
	body, err := json.Marshal(envelope)
	if err != nil {
		return fmt.Errorf("marshal outbox envelope: %w", err)
	}
	return storage.NewOutboxAppender(p.db.DBFor(ctx)).Append(ctx, messaging.StreamName(envelope.Producer), envelope.EventID, body)
}
