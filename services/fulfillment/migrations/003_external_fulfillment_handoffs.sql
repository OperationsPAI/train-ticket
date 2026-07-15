CREATE TABLE IF NOT EXISTS external_fulfillment_handoffs (
  producer   text NOT NULL,
  source_ref text NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (producer, source_ref)
);

CREATE INDEX IF NOT EXISTS idx_external_fulfillment_handoffs_status ON external_fulfillment_handoffs ((data->>'status'));
