CREATE TABLE IF NOT EXISTS risk_assessment_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_risk_assessment_subject_scenario ON risk_assessment_snapshots ((data->>'subjectRef'), (data->>'scenario'));
CREATE TABLE IF NOT EXISTS risk_block_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_risk_block_subject_scope ON risk_block_snapshots ((data->>'subjectRef'), (data->>'scope'));
CREATE TABLE IF NOT EXISTS risk_order_accounts (
  order_id text PRIMARY KEY,
  account_id text NOT NULL
);
CREATE TABLE IF NOT EXISTS risk_account_order_attempts (
  account_id text NOT NULL,
  occurred_at timestamptz NOT NULL,
  PRIMARY KEY (account_id, occurred_at)
);
CREATE INDEX IF NOT EXISTS idx_risk_account_order_attempts_recent ON risk_account_order_attempts (account_id, occurred_at DESC);
CREATE TABLE IF NOT EXISTS risk_account_lifts (
  account_id text PRIMARY KEY,
  lifted_at timestamptz NOT NULL
);
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
