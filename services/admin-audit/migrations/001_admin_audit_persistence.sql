CREATE TABLE IF NOT EXISTS operator_identity_snapshots (id text PRIMARY KEY, version bigint NOT NULL, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now());
CREATE UNIQUE INDEX IF NOT EXISTS idx_operator_identity_snapshots_email ON operator_identity_snapshots (lower(data->>'email'));
CREATE TABLE IF NOT EXISTS manual_action_snapshots (id text PRIMARY KEY, version bigint NOT NULL, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS audit_entry_snapshots (id text PRIMARY KEY, version bigint NOT NULL, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS idx_audit_entry_snapshots_resource_ref_recorded_at ON audit_entry_snapshots ((data->>'resourceRef'), ((data->>'recordedAt')::timestamptz) DESC);
CREATE TABLE IF NOT EXISTS outbox (seq bigserial PRIMARY KEY, event_id text NOT NULL UNIQUE, stream text NOT NULL, envelope jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz);
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished_seq ON outbox (seq) WHERE published_at IS NULL;
CREATE TABLE IF NOT EXISTS processed_events (event_id text PRIMARY KEY, stream text, processed_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS idempotency_records (key text PRIMARY KEY, request_hash text NOT NULL, status_code int NOT NULL, response_body jsonb, created_at timestamptz NOT NULL DEFAULT now());
