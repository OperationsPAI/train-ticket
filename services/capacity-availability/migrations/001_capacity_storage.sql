CREATE TABLE IF NOT EXISTS inventory_pool_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  scheduled_service_ref text GENERATED ALWAYS AS (data #>> '{identity,scheduledServiceRef}') STORED,
  service_segment_ref text GENERATED ALWAYS AS (data #>> '{identity,serviceSegmentRef}') STORED,
  route_segment_ref text GENERATED ALWAYS AS (data #>> '{identity,routeSegmentRef}') STORED,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_inventory_pool_availability_query
  ON inventory_pool_snapshots (scheduled_service_ref, service_segment_ref, route_segment_ref);

CREATE INDEX IF NOT EXISTS idx_inventory_pool_service_segment_ref
  ON inventory_pool_snapshots (service_segment_ref);

CREATE INDEX IF NOT EXISTS idx_inventory_pool_route_segment_ref
  ON inventory_pool_snapshots (route_segment_ref);

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
