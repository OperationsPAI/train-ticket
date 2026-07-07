CREATE TABLE IF NOT EXISTS supplier_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS carrier_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS contract_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_supplier_profile ON supplier_snapshots ((lower(data->>'profile')));
CREATE UNIQUE INDEX IF NOT EXISTS idx_carrier_code ON carrier_snapshots ((lower(data->>'code')));
CREATE UNIQUE INDEX IF NOT EXISTS idx_contract_ref ON contract_snapshots ((lower(data->>'contractNo')));
CREATE INDEX IF NOT EXISTS idx_supplier_status_id ON supplier_snapshots ((data->>'status'), id);
CREATE TABLE IF NOT EXISTS outbox (
  seq          bigserial PRIMARY KEY,
  event_id     text NOT NULL UNIQUE,
  stream       text NOT NULL,
  envelope     jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished_seq ON outbox (seq) WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS processed_events (
  event_id     text PRIMARY KEY,
  stream       text,
  processed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS idempotency_records (
  key          text PRIMARY KEY,
  request_hash text NOT NULL,
  status_code  int NOT NULL,
  response_body jsonb,
  created_at   timestamptz NOT NULL DEFAULT now()
);
