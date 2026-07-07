CREATE TABLE IF NOT EXISTS fare_rule_set_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fare_rule_set_snapshots_lookup
  ON fare_rule_set_snapshots ((data->>'channel'), (data->>'productCode'), (data->>'status'), (data->>'publishedAt') DESC, id DESC);

CREATE TABLE IF NOT EXISTS fare_quote_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fare_quote_snapshots_channel_product
  ON fare_quote_snapshots ((data->>'channel'), (data->>'productCode'));

CREATE TABLE IF NOT EXISTS adjustment_quote_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_adjustment_quote_snapshots_original
  ON adjustment_quote_snapshots ((data->>'originalQuoteId'));

CREATE TABLE IF NOT EXISTS fare_quote_segment_links (
  quote_id text NOT NULL REFERENCES fare_quote_snapshots(id) ON DELETE CASCADE,
  segment_ref text NOT NULL,
  PRIMARY KEY (quote_id, segment_ref)
);

CREATE INDEX IF NOT EXISTS idx_fare_quote_segment_links_segment_quote
  ON fare_quote_segment_links(segment_ref, quote_id DESC);

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
