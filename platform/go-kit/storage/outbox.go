package storage

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/trainticket/greenfield/platform/go-kit/messaging"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
)

type OutboxAppender struct {
	db DBTX
}

func NewOutboxAppender(db DBTX) *OutboxAppender { return &OutboxAppender{db: db} }

func (a *OutboxAppender) Append(ctx context.Context, stream string, eventID string, envelope json.RawMessage) error {
	if !json.Valid(envelope) {
		return fmt.Errorf("outbox envelope must be valid json")
	}
	_, err := a.db.Exec(ctx, `INSERT INTO outbox (event_id, stream, envelope) VALUES ($1, $2, $3) ON CONFLICT (event_id) DO NOTHING`, eventID, stream, envelope)
	return err
}

type OutboxRelay struct {
	db        DBTX
	redis     *redis.Client
	interval  time.Duration
	maxLen    int64
	pollCount int
}

func NewOutboxRelay(db DBTX, redisClient *redis.Client) *OutboxRelay {
	return &OutboxRelay{db: db, redis: redisClient, interval: 250 * time.Millisecond, maxLen: messaging.MaxLen}
}

func (r *OutboxRelay) WithInterval(interval time.Duration) *OutboxRelay {
	if interval > 0 {
		r.interval = interval
	}
	return r
}

func (r *OutboxRelay) Run(ctx context.Context) {
	if r.interval <= 0 || r.interval > 250*time.Millisecond {
		r.interval = 250 * time.Millisecond
	}
	ticker := time.NewTicker(r.interval)
	defer ticker.Stop()
	for {
		_ = r.PublishBatch(ctx)
		r.pollCount++
		if r.pollCount%20 == 0 {
			r.cleanup(ctx)
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (r *OutboxRelay) PublishBatch(ctx context.Context) error {
	if r == nil || r.redis == nil || r.db == nil {
		return fmt.Errorf("outbox relay requires postgres and redis")
	}
	rows, err := r.db.Query(ctx, `SELECT seq, stream, envelope FROM outbox WHERE published_at IS NULL ORDER BY seq LIMIT 100`)
	if err != nil {
		return err
	}
	defer rows.Close()

	type outboxRow struct {
		seq      int64
		stream   string
		envelope json.RawMessage
	}
	batch := make([]outboxRow, 0, 100)
	for rows.Next() {
		var row outboxRow
		if err := rows.Scan(&row.seq, &row.stream, &row.envelope); err != nil {
			return err
		}
		batch = append(batch, row)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	if len(batch) == 0 {
		return nil
	}

	pipe := r.redis.Pipeline()
	for _, row := range batch {
		pipe.XAdd(ctx, &redis.XAddArgs{Stream: row.stream, MaxLen: r.maxLen, Approx: true, Values: map[string]any{messaging.EnvelopeField: string(row.envelope)}})
	}
	if _, err := pipe.Exec(ctx); err != nil {
		return err
	}

	placeholders := make([]string, len(batch))
	args := make([]any, len(batch))
	for i, row := range batch {
		placeholders[i] = fmt.Sprintf("$%d", i+1)
		args[i] = row.seq
	}
	_, err = r.db.Exec(ctx, fmt.Sprintf(`UPDATE outbox SET published_at = now() WHERE seq IN (%s) AND published_at IS NULL`, strings.Join(placeholders, ", ")), args...)
	return err
}

// cleanupBatchSize is the rows per retention DELETE. Small enough to finish
// quickly even when the retention column is unindexed.
const cleanupBatchSize = 5000

// cleanupMaxBatches bounds one sweep pass per table; the next pass resumes.
const cleanupMaxBatches = 20

// cleanup is the retention sweep for the three platform tables.
//
// Deletes in BATCHES, and reports what it did. The previous version issued one
// unbounded DELETE per table and discarded the error return entirely, and on a
// long-running cluster that combination silently stopped working: journey-order's
// processed_events had reached 1,092,388 rows and 205 MB, with 1,085,529 of them
// past the 5-minute retention and the oldest 21 hours old. processed_at was
// unindexed, so the statement planned a Seq Scan over the whole table and tried
// to delete a million rows in one transaction; whatever went wrong was thrown
// away with the error.
//
// The cost of that lands on the hot path: every consumed event checks
// processed_events for deduplication, so a table that grows without bound makes
// every event handler in the service slower.
func (r *OutboxRelay) cleanup(ctx context.Context) {
	r.sweep(ctx, "outbox", `DELETE FROM outbox WHERE ctid IN (SELECT ctid FROM outbox `+
		`WHERE published_at IS NOT NULL AND published_at < now() - interval '30 seconds' LIMIT $1)`)
	r.sweep(ctx, "processed_events", `DELETE FROM processed_events WHERE ctid IN (SELECT ctid FROM processed_events `+
		`WHERE processed_at < now() - interval '5 minutes' LIMIT $1)`)
	r.sweep(ctx, "idempotency_records", `DELETE FROM idempotency_records WHERE ctid IN (SELECT ctid FROM idempotency_records `+
		`WHERE created_at < now() - interval '10 minutes' LIMIT $1)`)
}

// sweep runs one batched retention sweep. Uses `ctid IN (SELECT ... LIMIT n)`
// because a plain `DELETE ... LIMIT` is not valid in Postgres, and the subquery
// keeps the row set bounded whether or not the retention column is indexed.
func (r *OutboxRelay) sweep(ctx context.Context, table string, batchedDelete string) {
	var removed int64
	for batch := 0; batch < cleanupMaxBatches; batch++ {
		tag, err := r.db.Exec(ctx, batchedDelete, cleanupBatchSize)
		if err != nil {
			// Logged, not discarded. The silent version of this is why nobody
			// noticed the sweep had stopped for 21 hours.
			log.Printf("WARN %sretention sweep for %s failed after removing %d rows: %T: %v",
				goruntime.TraceLogFields(ctx), table, removed, err, err)
			return
		}
		affected := tag.RowsAffected()
		removed += affected
		if affected < cleanupBatchSize {
			return
		}
	}
	// Still behind after a full budget: the table is growing faster than the
	// sweep drains it, which is how the million-row backlog accumulated.
	log.Printf("WARN %sretention sweep for %s removed %d rows and hit its batch budget; "+
		"the table is still above retention. If this repeats, the retention column likely needs an index.",
		goruntime.TraceLogFields(ctx), table, removed)
}

type ProcessedEvents struct {
	db DBTX
}

func NewProcessedEvents(db DBTX) *ProcessedEvents { return &ProcessedEvents{db: db} }

func (p *ProcessedEvents) TryRecord(ctx context.Context, eventID string, stream string) (bool, error) {
	tag, err := p.db.Exec(ctx, `INSERT INTO processed_events (event_id, stream) VALUES ($1, $2) ON CONFLICT DO NOTHING`, eventID, stream)
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() == 1, nil
}
