CREATE TABLE IF NOT EXISTS post_sales_case_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_post_sales_case_snapshots_idempotency_key ON post_sales_case_snapshots ((data->>'idempotencyKey'));
CREATE INDEX IF NOT EXISTS idx_post_sales_case_snapshots_scope_order_items ON post_sales_case_snapshots USING gin ((data->'scope'->'orderItemRefs'));

CREATE TABLE IF NOT EXISTS post_sales_active_refunds (
  journey_order_id text PRIMARY KEY,
  case_id text NOT NULL UNIQUE,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_post_sales_active_refunds_case_id ON post_sales_active_refunds(case_id);
CREATE TABLE IF NOT EXISTS outbox (seq bigserial PRIMARY KEY, event_id text NOT NULL UNIQUE, stream text NOT NULL, envelope jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz);
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished_seq ON outbox (seq) WHERE published_at IS NULL;
CREATE TABLE IF NOT EXISTS processed_events (event_id text PRIMARY KEY, stream text, processed_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS idempotency_records (key text PRIMARY KEY, request_hash text NOT NULL, status_code int NOT NULL, response_body jsonb, created_at timestamptz NOT NULL DEFAULT now());
