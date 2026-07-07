CREATE TABLE IF NOT EXISTS inventory_pool_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS availability_snapshot_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS capacity_hold_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS outbox (
  seq          bigserial PRIMARY KEY,
  event_id     text NOT NULL UNIQUE,
  stream       text NOT NULL,
  envelope     jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished_seq
  ON outbox (seq)
  WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS processed_events (
  event_id     text PRIMARY KEY,
  stream       text,
  processed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS idempotency_records (
  key           text PRIMARY KEY,
  request_hash  text NOT NULL,
  status_code   int NOT NULL,
  response_body jsonb,
  created_at    timestamptz NOT NULL DEFAULT now()
);
