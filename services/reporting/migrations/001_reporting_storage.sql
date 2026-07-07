CREATE TABLE IF NOT EXISTS metric_definition_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_metric_definition_snapshots_category_id ON metric_definition_snapshots ((data->>'category'), id);
CREATE TABLE IF NOT EXISTS dashboard_read_model_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS consumed_event_log_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS reporting_rebuild_runs (
  rebuild_id text PRIMARY KEY,
  dashboard_id text NOT NULL,
  rebuilt_at timestamptz NOT NULL,
  status text NOT NULL,
  event_count bigint NOT NULL,
  digest text NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reporting_rebuild_runs_dashboard_rebuilt ON reporting_rebuild_runs (dashboard_id, rebuilt_at DESC);
CREATE TABLE IF NOT EXISTS outbox (
  seq bigserial PRIMARY KEY,
  event_id text NOT NULL UNIQUE,
  stream text NOT NULL,
  envelope jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished_seq ON outbox(seq) WHERE published_at IS NULL;
CREATE TABLE IF NOT EXISTS processed_events (
  event_id text PRIMARY KEY,
  stream text,
  processed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS idempotency_records (
  key text PRIMARY KEY,
  request_hash text NOT NULL,
  status_code int NOT NULL,
  response_body jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
