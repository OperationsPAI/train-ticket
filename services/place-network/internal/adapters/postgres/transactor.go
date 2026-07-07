package postgres

import (
	"context"
	"sync"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
)

type Transactor struct {
	pool *pgxpool.Pool
	mu   sync.Mutex
	tx   pgx.Tx
}

func NewTransactor(pool *pgxpool.Pool) *Transactor { return &Transactor{pool: pool} }

func (t *Transactor) DB() storage.DBTX {
	if t.tx != nil {
		return t.tx
	}
	return t.pool
}

func (t *Transactor) Within(ctx context.Context, fn func() error) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	tx, err := t.pool.Begin(ctx)
	if err != nil {
		return err
	}
	t.tx = tx
	defer func() {
		t.tx = nil
		_ = tx.Rollback(ctx)
	}()
	if err := fn(); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
