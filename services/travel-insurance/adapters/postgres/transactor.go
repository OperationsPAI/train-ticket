package postgres

import (
	"context"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
)

type txKey struct{}

type ContextDBProvider interface {
	DBFor(context.Context) storage.DBTX
}

type Transactor struct{ pool *pgxpool.Pool }

func NewTransactor(pool *pgxpool.Pool) *Transactor { return &Transactor{pool: pool} }
func (t *Transactor) DBFor(ctx context.Context) storage.DBTX {
	if tx, ok := ctx.Value(txKey{}).(pgx.Tx); ok {
		return tx
	}
	return t.pool
}
func (t *Transactor) Within(ctx context.Context, fn func(context.Context) error) error {
	return storage.WithTx(ctx, t.pool, func(tx pgx.Tx) error { return fn(context.WithValue(ctx, txKey{}, tx)) })
}
