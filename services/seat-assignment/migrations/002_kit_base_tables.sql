-- rust-kit runtime base tables (outbox relay, HTTP idempotency, consume
-- dedup). Migration 001 shipped without them; new DDL = new migration.
CREATE TABLE IF NOT EXISTS idempotency_records (key text PRIMARY KEY, request_hash text NOT NULL, status_code int NOT NULL, response_body jsonb, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS outbox (seq bigserial PRIMARY KEY, event_id text NOT NULL UNIQUE, stream text NOT NULL, envelope jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz);
CREATE TABLE IF NOT EXISTS processed_events (event_id text NOT NULL, stream text NOT NULL, processed_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (event_id, stream));
