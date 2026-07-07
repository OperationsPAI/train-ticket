CREATE TABLE IF NOT EXISTS booking_saga_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_booking_saga_snapshots_journey_order_id
  ON booking_saga_snapshots ((data->>'journeyOrderId'));
CREATE INDEX IF NOT EXISTS idx_booking_saga_snapshots_correlation_id
  ON booking_saga_snapshots ((data->>'correlationId'));

CREATE TABLE IF NOT EXISTS segment_booking_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_segment_booking_snapshots_saga_id
  ON segment_booking_snapshots ((data->>'sagaId'));
CREATE INDEX IF NOT EXISTS idx_segment_booking_snapshots_hold_id
  ON segment_booking_snapshots ((data->>'capacityHoldId'));
CREATE INDEX IF NOT EXISTS idx_segment_booking_snapshots_idempotency_key
  ON segment_booking_snapshots ((data->>'idempotencyKey'));

CREATE TABLE IF NOT EXISTS payment_intent_saga_refs (
  payment_intent_id text PRIMARY KEY,
  saga_id           text NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now()
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
