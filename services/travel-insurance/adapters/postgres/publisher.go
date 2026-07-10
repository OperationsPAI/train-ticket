package postgres

import (
	"context"
	"encoding/json"

	"github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
)

type OutboxPublisher struct{ provider DBProvider }

func NewOutboxPublisher(db storage.DBTX) *OutboxPublisher {
	return &OutboxPublisher{provider: staticProvider{db: db}}
}
func NewOutboxPublisherWithProvider(provider DBProvider) *OutboxPublisher {
	return &OutboxPublisher{provider: provider}
}

func (p *OutboxPublisher) Publish(ctx context.Context, envelope messaging.EventEnvelope) error {
	body, err := json.Marshal(envelope)
	if err != nil {
		return err
	}
	return storage.NewOutboxAppender(p.provider.DBFor(ctx)).Append(ctx, messaging.StreamName(envelope.Producer), envelope.EventID, body)
}
