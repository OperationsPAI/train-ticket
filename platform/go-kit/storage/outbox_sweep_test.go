package storage

import (
	"context"
	"errors"
	"log"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// sweepDB is a DBTX double that reports a scripted RowsAffected per Exec.
type sweepDB struct {
	script   func(call int) (int64, error)
	calls    int
	statutes []string
	args     [][]any
}

func (d *sweepDB) Exec(_ context.Context, sql string, args ...any) (pgconn.CommandTag, error) {
	d.calls++
	d.statutes = append(d.statutes, sql)
	d.args = append(d.args, args)
	affected, err := d.script(d.calls)
	if err != nil {
		return pgconn.CommandTag{}, err
	}
	return pgconn.NewCommandTag("DELETE " + itoa(affected)), nil
}

func itoa(n int64) string {
	if n == 0 {
		return "0"
	}
	var b []byte
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	return string(b)
}

func (d *sweepDB) Query(context.Context, string, ...any) (pgx.Rows, error) { return nil, nil }
func (d *sweepDB) QueryRow(context.Context, string, ...any) pgx.Row       { return nil }

func TestSweepStopsOnShortBatch(t *testing.T) {
	db := &sweepDB{script: func(int) (int64, error) { return 0, nil }}
	(&OutboxRelay{db: db}).sweep(context.Background(), "processed_events", "DELETE ... LIMIT $1")
	if db.calls != 1 {
		t.Fatalf("expected 1 statement for an already-drained table, got %d", db.calls)
	}
	if len(db.args[0]) != 1 || db.args[0][0] != cleanupBatchSize {
		t.Fatalf("batch size must be bound as a parameter, got %v", db.args[0])
	}
}

func TestSweepStopsOnPartialBatch(t *testing.T) {
	db := &sweepDB{script: func(int) (int64, error) { return cleanupBatchSize - 1, nil }}
	(&OutboxRelay{db: db}).sweep(context.Background(), "processed_events", "DELETE ... LIMIT $1")
	if db.calls != 1 {
		t.Fatalf("a short batch means caught up; expected 1 statement, got %d", db.calls)
	}
}

func TestSweepIsBoundedByBatchBudget(t *testing.T) {
	db := &sweepDB{script: func(int) (int64, error) { return cleanupBatchSize, nil }}
	(&OutboxRelay{db: db}).sweep(context.Background(), "processed_events", "DELETE ... LIMIT $1")
	if db.calls != cleanupMaxBatches {
		t.Fatalf("expected the pass to stop at the %d-batch budget, got %d", cleanupMaxBatches, db.calls)
	}
}

func TestSweepDrainsThenStops(t *testing.T) {
	db := &sweepDB{script: func(call int) (int64, error) {
		if call <= 2 {
			return cleanupBatchSize, nil
		}
		return 137, nil
	}}
	(&OutboxRelay{db: db}).sweep(context.Background(), "processed_events", "DELETE ... LIMIT $1")
	if db.calls != 3 {
		t.Fatalf("expected 2 full batches then a short one, got %d", db.calls)
	}
}

// The regression that mattered: an error must abort the sweep and be reported,
// not be discarded like the old `r.db.Exec(...)` with no error check.
func TestSweepStopsAndDoesNotPanicOnError(t *testing.T) {
	db := &sweepDB{script: func(call int) (int64, error) {
		if call == 2 {
			return 0, errors.New("connection reset by peer")
		}
		return cleanupBatchSize, nil
	}}
	(&OutboxRelay{db: db}).sweep(context.Background(), "processed_events", "DELETE ... LIMIT $1")
	if db.calls != 2 {
		t.Fatalf("expected the sweep to abort on the failing batch, got %d calls", db.calls)
	}
}

// A failing sweep must SAY so. The batching and the abort are only half the
// fix: the defect that let processed_events reach 1,092,388 rows was that the
// error went into a discarded return value, so a sweep that had been dead for 21
// hours looked exactly like one that had nothing to do.
//
// TestSweepStopsAndDoesNotPanicOnError covers the control flow and passes with
// or without the log line, so it does not cover this.
func TestSweepLogsTheFailureItAbortsOn(t *testing.T) {
	var captured strings.Builder
	restore := log.Writer()
	log.SetOutput(&captured)
	defer log.SetOutput(restore)

	db := &sweepDB{script: func(call int) (int64, error) {
		return 0, errors.New("connection reset by peer")
	}}
	(&OutboxRelay{db: db}).sweep(context.Background(), "processed_events", "DELETE ... LIMIT $1")

	out := captured.String()
	if !strings.Contains(out, "processed_events") {
		t.Errorf("the log line must name the table that failed; got %q", out)
	}
	if !strings.Contains(out, "connection reset by peer") {
		t.Errorf("the log line must carry the underlying error, or it explains nothing; got %q", out)
	}
}

func TestCleanupSweepsAllThreeTablesWithCtidSubquery(t *testing.T) {
	db := &sweepDB{script: func(int) (int64, error) { return 0, nil }}
	(&OutboxRelay{db: db}).cleanup(context.Background())
	if db.calls != 3 {
		t.Fatalf("expected one sweep per platform table, got %d", db.calls)
	}
	for _, want := range []string{"outbox", "processed_events", "idempotency_records"} {
		found := false
		for _, sql := range db.statutes {
			if strings.Contains(sql, "DELETE FROM "+want+" WHERE ctid IN (SELECT ctid FROM "+want) {
				found = true
			}
		}
		if !found {
			t.Fatalf("no batched ctid sweep for %s; statements: %v", want, db.statutes)
		}
	}
	for _, sql := range db.statutes {
		// A plain `DELETE ... LIMIT` is not valid Postgres; the bound must be
		// inside the ctid subquery.
		if !strings.Contains(sql, "LIMIT $1)") {
			t.Fatalf("limit must be parameterized inside the subquery: %s", sql)
		}
	}
}
