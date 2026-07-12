package postgres

import (
	"context"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
)

type txContextKey struct{}

type Transactor struct {
	pool *pgxpool.Pool
}

func NewTransactor(pool *pgxpool.Pool) *Transactor { return &Transactor{pool: pool} }

// DB returns the process-wide pool. Transactional work must use DBFor(ctx)
// with the context passed into the unit-of-work closure.
func (t *Transactor) DB() storage.DBTX { return t.pool }

func (t *Transactor) DBFor(ctx context.Context) storage.DBTX {
	if tx, ok := txFromContext(ctx); ok {
		return tx
	}
	return t.pool
}

func (t *Transactor) Within(ctx context.Context, fn func(context.Context) error) error {
	tx, err := t.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := fn(contextWithTx(ctx, tx)); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func contextWithTx(ctx context.Context, tx storage.DBTX) context.Context {
	return context.WithValue(ctx, txContextKey{}, tx)
}

func txFromContext(ctx context.Context) (storage.DBTX, bool) {
	tx, ok := ctx.Value(txContextKey{}).(storage.DBTX)
	return tx, ok
}
