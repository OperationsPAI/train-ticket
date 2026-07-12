CREATE TABLE IF NOT EXISTS risk_ip_order_attempts (
  source_ip text NOT NULL,
  account_id text NOT NULL,
  occurred_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_risk_ip_order_attempts_recent ON risk_ip_order_attempts (source_ip, occurred_at DESC);
