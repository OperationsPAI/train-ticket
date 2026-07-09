CREATE TABLE IF NOT EXISTS ancillary_catalog_items (id text PRIMARY KEY, version bigint NOT NULL, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS ancillary_offers (id text PRIMARY KEY, version bigint NOT NULL, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS ancillary_order_items (id text PRIMARY KEY, version bigint NOT NULL, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS idempotency_records (key text PRIMARY KEY, request_hash text NOT NULL, status_code integer NOT NULL, response_body jsonb, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS outbox (seq bigserial PRIMARY KEY, event_id text UNIQUE NOT NULL, stream text NOT NULL, envelope jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz);
CREATE TABLE IF NOT EXISTS processed_events (event_id text PRIMARY KEY, stream text, processed_at timestamptz NOT NULL DEFAULT now());
-- Text-ordered expiry: RFC3339 UTC strings sort chronologically, and
-- timestamptz casts are not IMMUTABLE in index expressions (REQ-081A ruling).
CREATE INDEX IF NOT EXISTS idx_ancillary_offers_expiry ON ancillary_offers ((data->>'status'), (data->>'expiresAt'));
CREATE INDEX IF NOT EXISTS idx_ancillary_order_items_journey ON ancillary_order_items ((data->>'journeyOrderId'));
