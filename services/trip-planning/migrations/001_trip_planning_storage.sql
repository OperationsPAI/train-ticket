CREATE TABLE IF NOT EXISTS plan_index_events (
  event_id text PRIMARY KEY,
  event_type text NOT NULL,
  producer text NOT NULL,
  occurred_at text NOT NULL,
  payload jsonb NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS plan_services (
  scheduled_service_ref text PRIMARY KEY,
  version bigint NOT NULL DEFAULT 1,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS plan_segments (
  segment_ref text PRIMARY KEY,
  version bigint NOT NULL DEFAULT 1,
  scheduled_service_ref text,
  origin_stop_ref text NOT NULL,
  destination_stop_ref text NOT NULL,
  departure_time timestamptz NOT NULL,
  departure_date date NOT NULL,
  arrival_time timestamptz NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_plan_segments_search ON plan_segments (departure_date, origin_stop_ref, destination_stop_ref);
CREATE TABLE IF NOT EXISTS plan_nodes (
  node_id text PRIMARY KEY,
  version bigint NOT NULL DEFAULT 1,
  place_id text NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_plan_nodes_place ON plan_nodes (place_id, node_id);
CREATE TABLE IF NOT EXISTS plan_mct_rules (
  mct_rule_id text NOT NULL,
  version integer NOT NULL,
  status text NOT NULL,
  from_node_type text NOT NULL,
  to_node_type text NOT NULL,
  transfer_category text NOT NULL,
  minimum_minutes integer NOT NULL,
  valid_from timestamptz,
  valid_until timestamptz,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (mct_rule_id, version)
);
CREATE INDEX IF NOT EXISTS idx_plan_mct_rules_lookup ON plan_mct_rules (status, from_node_type, to_node_type, transfer_category);
CREATE TABLE IF NOT EXISTS itinerary_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
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
