CREATE TABLE IF NOT EXISTS ride_request_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ride_request_active_intent ON ride_request_snapshots ((data->>'riderAccountId'), (data->>'intentFingerprint')) WHERE data->>'status' IN ('REQUESTED','MATCHING','ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','PICKED_UP','DRIVER_CANCELLED');
CREATE INDEX IF NOT EXISTS idx_ride_request_rider_created ON ride_request_snapshots ((data->>'riderAccountId'), (data->>'createdAt'), (data->>'rideRequestId'));
CREATE INDEX IF NOT EXISTS idx_ride_request_status_updated ON ride_request_snapshots ((data->>'status'), (data->>'updatedAt'));
CREATE TABLE IF NOT EXISTS outbox (seq bigserial PRIMARY KEY, event_id text NOT NULL UNIQUE, stream text NOT NULL, envelope jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz);
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished_seq ON outbox (seq) WHERE published_at IS NULL;
CREATE TABLE IF NOT EXISTS processed_events (event_id text PRIMARY KEY, stream text, processed_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS idempotency_records (key text PRIMARY KEY, request_hash text NOT NULL, status_code int NOT NULL, response_body jsonb, created_at timestamptz NOT NULL DEFAULT now());
