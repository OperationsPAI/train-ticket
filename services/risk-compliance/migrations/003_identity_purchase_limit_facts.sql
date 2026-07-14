CREATE TABLE IF NOT EXISTS risk_purchase_limit_facts (
  fact_id text PRIMARY KEY,
  status text NOT NULL CHECK (status IN ('RECORDED', 'CONFIRMED', 'RELEASED', 'MISSED', 'FAILED')),
  traveler_id text,
  order_intent_id text,
  journey_date text,
  product_code text,
  segment_refs jsonb NOT NULL DEFAULT '[]'::jsonb,
  limit_policy_version text,
  occurred_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_risk_purchase_limit_facts_traveler_active
  ON risk_purchase_limit_facts(traveler_id, occurred_at DESC)
  WHERE status IN ('RECORDED', 'CONFIRMED', 'MISSED', 'FAILED');

CREATE TABLE IF NOT EXISTS risk_processed_purchase_limit_facts (
  event_id text NOT NULL,
  fact_id text NOT NULL,
  event_type text NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id, fact_id),
  UNIQUE (fact_id, event_type)
);
