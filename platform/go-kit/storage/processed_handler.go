package storage

import (
	"context"

	"github.com/trainticket/greenfield/platform/go-kit/messaging"
)

type TxRunner interface {
	Within(context.Context, func(context.Context) error) error
}

type ContextDBProvider interface {
	DBFor(context.Context) DBTX
}

func ProcessedEventHandler(txRunner TxRunner, db ContextDBProvider, stream string, next messaging.Handler) messaging.Handler {
	return func(ctx context.Context, envelope messaging.EventEnvelope) error {
		return txRunner.Within(ctx, func(txCtx context.Context) error {
			fresh, err := NewProcessedEvents(db.DBFor(txCtx)).TryRecord(txCtx, envelope.EventID, stream)
			if err != nil || !fresh {
				return err
			}
			return next(txCtx, envelope)
		})
	}
}
