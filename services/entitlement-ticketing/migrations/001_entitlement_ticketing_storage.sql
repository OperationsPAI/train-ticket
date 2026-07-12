CREATE TABLE IF NOT EXISTS entitlement_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  journey_order_id text GENERATED ALWAYS AS (data ->> 'journeyOrderId') STORED,
  segment_booking_id text GENERATED ALWAYS AS (data ->> 'segmentBookingId') STORED,
  credential_no text GENERATED ALWAYS AS (data #>> '{credentialRef,credentialNo}') STORED,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_entitlement_journey_order_id
  ON entitlement_snapshots (journey_order_id);

CREATE INDEX IF NOT EXISTS idx_entitlement_segment_booking_id
  ON entitlement_snapshots (segment_booking_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_entitlement_credential_no_unique
  ON entitlement_snapshots (credential_no)
  WHERE credential_no IS NOT NULL;

CREATE SEQUENCE IF NOT EXISTS entitlement_ticket_no_seq;

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
