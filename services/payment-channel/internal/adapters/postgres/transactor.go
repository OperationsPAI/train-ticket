package postgres

import (
	"context"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
)

type txKey struct{}
type Transactor struct{ pool *pgxpool.Pool }

func NewTransactor(pool *pgxpool.Pool) *Transactor { return &Transactor{pool: pool} }
func (t *Transactor) DBFor(ctx context.Context) storage.DBTX {
	if tx, ok := ctx.Value(txKey{}).(storage.DBTX); ok {
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
	if err := fn(context.WithValue(ctx, txKey{}, tx)); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
