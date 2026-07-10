CREATE TABLE IF NOT EXISTS insurance_products (
  id text PRIMARY KEY,
  product_code text NOT NULL,
  version integer NOT NULL,
  status text NOT NULL,
  data jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (product_code, version)
);

CREATE TABLE IF NOT EXISTS policies (
  id text PRIMARY KEY,
  journey_order_id text NOT NULL,
  account_id text NOT NULL,
  status text NOT NULL,
  selection_fingerprint text NOT NULL,
  data jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS policies_active_selection_uidx
  ON policies (selection_fingerprint)
  WHERE status NOT IN ('CLOSED', 'SURRENDERED', 'UNDERWRITING_FAILED');
CREATE INDEX IF NOT EXISTS policies_journey_order_idx ON policies (journey_order_id);

CREATE TABLE IF NOT EXISTS claims (
  id text PRIMARY KEY,
  policy_id text NOT NULL,
  claim_type text NOT NULL,
  trigger_fact_key text NOT NULL,
  status text NOT NULL,
  data jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS claims_active_fact_uidx
  ON claims (policy_id, claim_type, trigger_fact_key)
  WHERE status NOT IN ('CLOSED', 'REJECTED', 'FAILED');

CREATE TABLE IF NOT EXISTS payout_advices (
  id text PRIMARY KEY,
  claim_id text NOT NULL,
  policy_id text NOT NULL,
  status text NOT NULL,
  data jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS insurance_offers (
  id text PRIMARY KEY,
  journey_order_id text NOT NULL,
  account_id text NOT NULL,
  data jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS insurance_offers_journey_order_idx ON insurance_offers (journey_order_id);

CREATE TABLE IF NOT EXISTS outbox (
  seq bigserial PRIMARY KEY,
  event_id text NOT NULL UNIQUE,
  stream text NOT NULL,
  envelope jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);
CREATE INDEX IF NOT EXISTS outbox_unpublished_idx ON outbox (seq) WHERE published_at IS NULL;

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
  response_body jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
