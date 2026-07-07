package postgres

import (
	"context"
	"encoding/json"
	"fmt"

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
	body, err := json.Marshal(envelope)
	if err != nil {
		return fmt.Errorf("marshal outbox envelope: %w", err)
	}
	return storage.NewOutboxAppender(p.db.DBFor(ctx)).Append(ctx, kitmessaging.StreamName(envelope.Producer), envelope.EventID, body)
}
