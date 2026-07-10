package postgres

import (
	"context"
	"encoding/json"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
)

type OutboxPublisher struct{ db ContextDBProvider }

func NewOutboxPublisherWithProvider(db ContextDBProvider) *OutboxPublisher {
	return &OutboxPublisher{db: db}
}
func (p *OutboxPublisher) Publish(ctx context.Context, envelope kitmsg.EventEnvelope) error {
	body, err := json.Marshal(envelope)
	if err != nil {
		return err
	}
	return storage.NewOutboxAppender(p.db.DBFor(ctx)).Append(ctx, kitmsg.StreamName(envelope.Producer), envelope.EventID, body)
}
