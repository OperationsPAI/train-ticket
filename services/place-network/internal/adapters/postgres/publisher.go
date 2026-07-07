package postgres

import (
	"context"
	"encoding/json"
	"fmt"

	kitmessaging "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

type OutboxPublisher struct{ db DBProvider }

func NewOutboxPublisher(db storage.DBTX) *OutboxPublisher {
	return NewOutboxPublisherWithProvider(staticDB{db: db})
}
func NewOutboxPublisherWithProvider(db DBProvider) *OutboxPublisher { return &OutboxPublisher{db: db} }

func (p *OutboxPublisher) Publish(envelope domain.EventEnvelope) error {
	body, err := json.Marshal(envelope)
	if err != nil {
		return fmt.Errorf("marshal outbox envelope: %w", err)
	}
	return storage.NewOutboxAppender(p.db.DB()).Append(context.Background(), kitmessaging.StreamName(envelope.Producer), envelope.EventID, body)
}
