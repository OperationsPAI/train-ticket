package postgres

import (
	"context"
	"sync"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
)

type contextDB string

func (d contextDB) Exec(context.Context, string, ...any) (pgconn.CommandTag, error) {
	return pgconn.NewCommandTag("UPDATE 1"), nil
}
func (d contextDB) Query(context.Context, string, ...any) (pgx.Rows, error) { return nil, nil }
func (d contextDB) QueryRow(context.Context, string, ...any) pgx.Row        { return nil }

func TestContextTransactionIsolation(t *testing.T) {
	transactor := &Transactor{}
	tx1 := contextDB("tx1")
	tx2 := contextDB("tx2")
	ctx1 := contextWithTx(context.Background(), tx1)
	ctx2 := contextWithTx(context.Background(), tx2)

	if _, ok := txFromContext(context.Background()); ok {
		t.Fatalf("expected no transaction outside unit of work")
	}

	start := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(2)
	for _, tc := range []struct {
		ctx  context.Context
		want storage.DBTX
	}{
		{ctx: ctx1, want: storage.DBTX(tx1)},
		{ctx: ctx2, want: storage.DBTX(tx2)},
	} {
		tc := tc
		go func() {
			defer wg.Done()
			<-start
			for i := 0; i < 100; i++ {
				got := transactor.DBFor(tc.ctx)
				if got != tc.want {
					t.Errorf("DBFor returned wrong transaction: got %v want %v", got, tc.want)
				}
			}
		}()
	}
	close(start)
	wg.Wait()
}
