package storage

import (
	"context"
	"encoding/json"
	"strings"

	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
)

type IdempotencyStore struct {
	db DBTX
}

func NewIdempotencyStore(db DBTX) *IdempotencyStore { return &IdempotencyStore{db: db} }

func (s *IdempotencyStore) Get(ctx context.Context, key string) (idempotency.Record, bool) {
	var record idempotency.Record
	var body []byte
	row := s.db.QueryRow(ctx, `SELECT request_hash, status_code, COALESCE(response_body::text, '') FROM idempotency_records WHERE key = $1`, strings.TrimSpace(key))
	var bodyText string
	if err := row.Scan(&record.Fingerprint, &record.Status, &bodyText); err != nil {
		return idempotency.Record{}, false
	}
	if bodyText != "" {
		body = []byte(bodyText)
	}
	record.Body = body
	return record, true
}

func (s *IdempotencyStore) Put(ctx context.Context, key string, record idempotency.Record) error {
	var body any
	if len(record.Body) > 0 && json.Valid(record.Body) {
		body = json.RawMessage(record.Body)
	}
	_, err := s.db.Exec(ctx, `INSERT INTO idempotency_records (key, request_hash, status_code, response_body) VALUES ($1, $2, $3, $4) ON CONFLICT (key) DO NOTHING`, strings.TrimSpace(key), record.Fingerprint, record.Status, body)
	return err
}
