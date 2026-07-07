CREATE TABLE IF NOT EXISTS place_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS transport_node_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_place_snapshots_status ON place_snapshots ((data->>'status'));
CREATE INDEX IF NOT EXISTS idx_place_snapshots_created_at ON place_snapshots ((data->>'createdAt'));
CREATE INDEX IF NOT EXISTS idx_transport_node_snapshots_place_id ON transport_node_snapshots ((data->>'placeId'));

CREATE TABLE IF NOT EXISTS outbox (
  seq          bigserial PRIMARY KEY,
  event_id     text NOT NULL UNIQUE,
  stream       text NOT NULL,
  envelope     jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);

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
