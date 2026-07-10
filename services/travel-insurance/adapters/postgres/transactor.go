package postgres

import (
	"context"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
)

type Transactor struct{ pool *pgxpool.Pool }

type txKey struct{}

func NewTransactor(pool *pgxpool.Pool) *Transactor { return &Transactor{pool: pool} }

func (t *Transactor) Within(ctx context.Context, fn func(context.Context) error) error {
	if _, ok := ctx.Value(txKey{}).(pgx.Tx); ok {
		return fn(ctx)
	}
	tx, err := t.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() {
		if tx != nil {
			_ = tx.Rollback(ctx)
		}
	}()
	txCtx := context.WithValue(ctx, txKey{}, tx)
	if err := fn(txCtx); err != nil {
		return err
	}
	err = tx.Commit(ctx)
	tx = nil
	return err
}

func (t *Transactor) DBFor(ctx context.Context) storage.DBTX {
	if tx, ok := ctx.Value(txKey{}).(pgx.Tx); ok {
		return tx
	}
	return t.pool
}
