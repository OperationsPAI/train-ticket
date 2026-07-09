CREATE TABLE IF NOT EXISTS risk_policy_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_risk_policy_status ON risk_policy_snapshots ((data->>'status'));
