package storage

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/trainticket/greenfield/platform/go-kit/messaging"
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
	db       DBTX
	redis    *redis.Client
	interval time.Duration
	maxLen   int64
}

func NewOutboxRelay(db DBTX, redisClient *redis.Client) *OutboxRelay {
	return &OutboxRelay{db: db, redis: redisClient, interval: 250 * time.Millisecond, maxLen: 100000}
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
