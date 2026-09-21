package storage

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

type fakeDB struct {
	rows int64
	// statements records every statement executed, so a test can assert on the
	// SQL and not only on what the call returned.
	statements *[]string
}

func (f fakeDB) Exec(_ context.Context, sql string, _ ...any) (pgconn.CommandTag, error) {
	if f.statements != nil {
		*f.statements = append(*f.statements, sql)
	}
	return pgconn.NewCommandTag("INSERT 0 " + string(byte('0'+f.rows))), nil
}
func (f fakeDB) Query(context.Context, string, ...any) (pgx.Rows, error) { return nil, nil }
func (f fakeDB) QueryRow(context.Context, string, ...any) pgx.Row        { return nil }

func TestSnapshotSaveConflictOnZeroRows(t *testing.T) {
	repo := NewSnapshotRepository(fakeDB{rows: 0}, "place_snapshots")
	_, err := repo.Save(context.Background(), "plc-1", 1, []byte(`{"id":"plc-1"}`))
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("expected ErrConflict, got %v", err)
	}
}

// TestSnapshotInsertDoesNotAbortTheTransaction pins the statement rather than
// the return value, because the return value was already right.
//
// A bare INSERT whose unique violation is raised and then classified as
// ErrConflict aborts the surrounding Postgres transaction. A caller that
// treats the conflict as an expected outcome and continues then gets
// pgx.ErrTxCommitRollback from the commit. provider-integration does exactly
// that for a re-delivered event, and every such commit failed: 9063 messages
// pending on events:journey-order, 140 dead-lettered, and 873 purchase
// journeys timing out on a reservation that was never confirmed.
func TestSnapshotInsertDoesNotAbortTheTransaction(t *testing.T) {
	var statements []string
	repo := NewSnapshotRepository(fakeDB{rows: 1, statements: &statements}, "place_snapshots")
	if err := repo.Insert(context.Background(), "plc-1", []byte(`{"id":"plc-1"}`)); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(statements) != 1 {
		t.Fatalf("got %d statements, want 1", len(statements))
	}
	if !strings.Contains(statements[0], "ON CONFLICT") {
		t.Errorf("insert is %q, and without ON CONFLICT a duplicate aborts the "+
			"transaction the caller is still inside", statements[0])
	}
}

// TestSnapshotInsertReportsAConflictFromTheRowCount: with ON CONFLICT DO
// NOTHING the duplicate is no longer an error, so the conflict has to be read
// off the row count. A caller that treats it as a failure must still see one.
func TestSnapshotInsertReportsAConflictFromTheRowCount(t *testing.T) {
	repo := NewSnapshotRepository(fakeDB{rows: 0}, "place_snapshots")
	err := repo.Insert(context.Background(), "plc-1", []byte(`{"id":"plc-1"}`))
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("expected ErrConflict, got %v", err)
	}
}

func TestSnapshotSaveReturnsNextVersion(t *testing.T) {
	repo := NewSnapshotRepository(fakeDB{rows: 1}, "place_snapshots")
	version, err := repo.Save(context.Background(), "plc-1", 7, []byte(`{"id":"plc-1"}`))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if version != 8 {
		t.Fatalf("expected next version 8, got %d", version)
	}
}
