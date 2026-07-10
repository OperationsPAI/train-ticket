package postgres

import (
	"context"
	"encoding/json"
	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
)

type OutboxPublisher struct{ tx *Transactor }

func NewOutboxPublisher(tx *Transactor) *OutboxPublisher { return &OutboxPublisher{tx: tx} }
func (p *OutboxPublisher) Publish(ctx context.Context, env kitmsg.EventEnvelope) error {
	b, err := json.Marshal(env)
	if err != nil {
		return err
	}
	return storage.NewOutboxAppender(p.tx.DBFor(ctx)).Append(ctx, "events:"+env.Producer, env.EventID, b)
}
