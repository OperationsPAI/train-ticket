CREATE TABLE IF NOT EXISTS risk_evaluation_snapshots (
  evaluation_id text PRIMARY KEY,
  order_id text NOT NULL,
  account_id text NOT NULL,
  verdict text NOT NULL CHECK (verdict IN ('PASS', 'CHALLENGE', 'BLOCK')),
  score int NOT NULL CHECK (score >= 0 AND score <= 100),
  triggered_rules jsonb NOT NULL,
  signals jsonb NOT NULL,
  evaluated_at timestamptz NOT NULL,
  overridden_by text,
  override_reason text,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_risk_evaluation_order ON risk_evaluation_snapshots(order_id);
CREATE INDEX IF NOT EXISTS idx_risk_evaluation_account_time ON risk_evaluation_snapshots(account_id, evaluated_at DESC);

CREATE TABLE IF NOT EXISTS risk_account_profiles (
  account_id text PRIMARY KEY,
  registered_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS risk_route_booking_history (
  order_id text PRIMARY KEY,
  account_id text NOT NULL,
  origin text NOT NULL,
  destination text NOT NULL,
  traveler_refs jsonb NOT NULL,
  departure_date text NOT NULL,
  occurred_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_risk_route_booking_history_account_route_time
  ON risk_route_booking_history(account_id, origin, destination, occurred_at DESC);

CREATE TABLE IF NOT EXISTS risk_payment_attempt_history (
  account_id text NOT NULL,
  occurred_at timestamptz NOT NULL,
  PRIMARY KEY(account_id, occurred_at)
);
CREATE INDEX IF NOT EXISTS idx_risk_payment_attempt_history_recent ON risk_payment_attempt_history(account_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS risk_refund_history (
  account_id text NOT NULL,
  occurred_at timestamptz NOT NULL,
  PRIMARY KEY(account_id, occurred_at)
);
CREATE INDEX IF NOT EXISTS idx_risk_refund_history_recent ON risk_refund_history(account_id, occurred_at DESC);
