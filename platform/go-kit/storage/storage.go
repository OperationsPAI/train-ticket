package storage

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

var ErrConflict = errors.New("storage optimistic concurrency conflict")

// DBTX is the minimal pgx surface used by the helpers. It is implemented by
// *pgxpool.Pool and pgx.Tx.
type DBTX interface {
	Exec(context.Context, string, ...any) (pgconn.CommandTag, error)
	Query(context.Context, string, ...any) (pgx.Rows, error)
	QueryRow(context.Context, string, ...any) pgx.Row
}

func NewPool(ctx context.Context, databaseURL string) (*pgxpool.Pool, error) {
	databaseURL = strings.TrimSpace(databaseURL)
	if databaseURL == "" {
		return nil, fmt.Errorf("DATABASE_URL is required")
	}
	cfg, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, fmt.Errorf("parse DATABASE_URL: %w", err)
	}
	if v := os.Getenv("PG_MAX_POOL_SIZE"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			cfg.MaxConns = int32(n)
		}
	}
	cfg.MaxConnLifetime = 10 * time.Minute
	cfg.MaxConnIdleTime = 5 * time.Minute
	cfg.HealthCheckPeriod = 30 * time.Second
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("create postgres pool: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("ping postgres: %w", err)
	}
	return pool, nil
}

func ReadyCheck(pool *pgxpool.Pool, migrationsReady func() bool) func(context.Context) error {
	return func(ctx context.Context) error {
		if pool == nil {
			return fmt.Errorf("postgres pool is not initialized")
		}
		if migrationsReady != nil && !migrationsReady() {
			return fmt.Errorf("schema migrations are not ready")
		}
		var one int
		if err := pool.QueryRow(ctx, `SELECT 1`).Scan(&one); err != nil {
			return err
		}
		if one != 1 {
			return fmt.Errorf("postgres readiness probe returned %d", one)
		}
		return nil
	}
}

type Snapshot struct {
	ID      string
	Version int64
	Data    json.RawMessage
}

type SnapshotRepository struct {
	db    DBTX
	table string
}

func NewSnapshotRepository(db DBTX, table string) *SnapshotRepository {
	return &SnapshotRepository{db: db, table: mustIdentifier(table)}
}

func (r *SnapshotRepository) DB() DBTX { return r.db }

func (r *SnapshotRepository) Get(ctx context.Context, id string) (Snapshot, bool, error) {
	var snap Snapshot
	row := r.db.QueryRow(ctx, fmt.Sprintf("SELECT id, version, data FROM %s WHERE id = $1", r.table), id)
	if err := row.Scan(&snap.ID, &snap.Version, &snap.Data); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return Snapshot{}, false, nil
		}
		return Snapshot{}, false, err
	}
	return snap, true, nil
}

func (r *SnapshotRepository) Insert(ctx context.Context, id string, data []byte) error {
	_, err := r.db.Exec(ctx, fmt.Sprintf("INSERT INTO %s (id, version, data) VALUES ($1, 1, $2)", r.table), id, json.RawMessage(data))
	if err != nil {
		if isUniqueViolation(err) {
			return ErrConflict
		}
		return err
	}
	return nil
}

func (r *SnapshotRepository) Save(ctx context.Context, id string, expectedVersion int64, data []byte) (int64, error) {
	tag, err := r.db.Exec(ctx, fmt.Sprintf("UPDATE %s SET version = version + 1, data = $2, updated_at = now() WHERE id = $1 AND version = $3", r.table), id, json.RawMessage(data), expectedVersion)
	if err != nil {
		return 0, err
	}
	if tag.RowsAffected() == 0 {
		return 0, ErrConflict
	}
	return expectedVersion + 1, nil
}

type Migration struct {
	Version string
	SQL     string
}

type MigrationRunner struct {
	pool  *pgxpool.Pool
	ready bool
}

func NewMigrationRunner(pool *pgxpool.Pool) *MigrationRunner { return &MigrationRunner{pool: pool} }
func (r *MigrationRunner) Ready() bool                       { return r != nil && r.ready }

func (r *MigrationRunner) Run(ctx context.Context, migrations []Migration) error {
	if r == nil || r.pool == nil {
		return fmt.Errorf("postgres pool is required")
	}
	r.ready = false
	sorted := append([]Migration(nil), migrations...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].Version < sorted[j].Version })
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())`); err != nil {
		return err
	}
	for _, migration := range sorted {
		version := strings.TrimSpace(migration.Version)
		if version == "" || strings.TrimSpace(migration.SQL) == "" {
			return fmt.Errorf("migration version and sql are required")
		}
		var alreadyApplied bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM schema_migrations WHERE version = $1)`, version).Scan(&alreadyApplied); err != nil {
			return err
		}
		if alreadyApplied {
			continue
		}
		if _, err := tx.Exec(ctx, migration.SQL); err != nil {
			return fmt.Errorf("apply migration %s: %w", version, err)
		}
		if _, err := tx.Exec(ctx, `INSERT INTO schema_migrations (version) VALUES ($1)`, version); err != nil {
			return err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	r.ready = true
	return nil
}

func LoadMigrations(fsys fs.FS, dir string) ([]Migration, error) {
	entries, err := fs.ReadDir(fsys, dir)
	if err != nil {
		return nil, err
	}
	migrations := make([]Migration, 0, len(entries))
	for _, entry := range entries {
		name := entry.Name()
		if entry.IsDir() || !strings.HasSuffix(name, ".sql") {
			continue
		}
		body, err := fs.ReadFile(fsys, path.Join(dir, name))
		if err != nil {
			return nil, err
		}
		migrations = append(migrations, Migration{Version: strings.TrimSuffix(name, ".sql"), SQL: string(body)})
	}
	sort.Slice(migrations, func(i, j int) bool { return migrations[i].Version < migrations[j].Version })
	return migrations, nil
}

func WithTx(ctx context.Context, pool *pgxpool.Pool, fn func(pgx.Tx) error) error {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := fn(tx); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func mustIdentifier(identifier string) string {
	identifier = strings.TrimSpace(identifier)
	if identifier == "" {
		panic("sql identifier is required")
	}
	for _, r := range identifier {
		if (r < 'a' || r > 'z') && (r < '0' || r > '9') && r != '_' {
			panic("unsafe sql identifier: " + identifier)
		}
	}
	return identifier
}

func isUniqueViolation(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23505"
}
