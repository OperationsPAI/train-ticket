package storage

import (
	"context"

	"github.com/trainticket/greenfield/platform/go-kit/messaging"
)

type TxRunner interface {
	Within(context.Context, func() error) error
}

func ProcessedEventHandler(txRunner TxRunner, guard *ProcessedEvents, stream string, next messaging.Handler) messaging.Handler {
	return func(ctx context.Context, envelope messaging.EventEnvelope) error {
		return txRunner.Within(ctx, func() error {
			fresh, err := guard.TryRecord(ctx, envelope.EventID, stream)
			if err != nil || !fresh {
				return err
			}
			return next(ctx, envelope)
		})
	}
}
