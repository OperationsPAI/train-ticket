CREATE TABLE IF NOT EXISTS plan_mct_rules (
  mct_rule_id text PRIMARY KEY,
  version bigint NOT NULL,
  status text NOT NULL CHECK (status IN ('PUBLISHED', 'RETIRED')),
  from_node_type text,
  to_node_type text,
  transfer_category text,
  minimum_minutes bigint,
  conditions jsonb,
  valid_from timestamptz,
  valid_until timestamptz,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_plan_mct_rules_active
  ON plan_mct_rules (from_node_type, to_node_type, transfer_category, valid_from)
  WHERE status = 'PUBLISHED';
