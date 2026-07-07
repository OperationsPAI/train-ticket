package storage

import (
	"context"
	"errors"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

type fakeDB struct{ rows int64 }

func (f fakeDB) Exec(context.Context, string, ...any) (pgconn.CommandTag, error) {
	return pgconn.NewCommandTag("UPDATE " + string(byte('0'+f.rows))), nil
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
