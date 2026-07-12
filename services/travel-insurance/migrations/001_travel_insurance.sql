CREATE TABLE IF NOT EXISTS insurance_product_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL DEFAULT 1,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS policy_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL DEFAULT 1,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_policy_uniqueness ON policy_snapshots ((data->>'ancillaryOrderItemId'), (data->>'travelerRef'), (data->>'productVersion'));
CREATE INDEX IF NOT EXISTS idx_policy_delay_segment ON policy_snapshots ((data->>'productCode'), (data->>'status'));
CREATE INDEX IF NOT EXISTS idx_policy_journey_status ON policy_snapshots ((data->>'journeyOrderId'), (data->>'status'));

CREATE TABLE IF NOT EXISTS claim_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL DEFAULT 1,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_claim_active_lookup ON claim_snapshots ((data->>'policyId'), (data->>'claimType'), (data->>'triggerFactKey'));

CREATE TABLE IF NOT EXISTS payout_advice_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL DEFAULT 1,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS underwriting_request_log (
  policy_id text NOT NULL,
  operation text NOT NULL,
  idempotency_key text NOT NULL,
  response_summary jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (policy_id, operation, idempotency_key)
);

CREATE TABLE IF NOT EXISTS outbox (
  seq bigserial PRIMARY KEY,
  event_id text NOT NULL UNIQUE,
  stream text NOT NULL,
  envelope jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox (seq) WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS processed_events (
  event_id text NOT NULL,
  stream text NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id, stream)
);

CREATE TABLE IF NOT EXISTS idempotency_records (
  key text PRIMARY KEY,
  request_hash text NOT NULL,
  status_code integer NOT NULL,
  response_body bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
