CREATE TABLE IF NOT EXISTS compensation_mismatch_checkpoints (
  case_id                 text PRIMARY KEY,
  journey_order_id        text NOT NULL,
  case_status             text NOT NULL,
  mismatch_code           text NOT NULL,
  reason                  text NOT NULL,
  approved_currency       text NOT NULL,
  approved_minor_units    bigint NOT NULL CHECK (approved_minor_units >= 0),
  refund_evidence         jsonb NOT NULL,
  wallet_evidence         jsonb NOT NULL,
  rights_evidence         jsonb NOT NULL,
  operation_evidence      jsonb NOT NULL,
  correlation_id          text NOT NULL,
  detected_at             timestamptz NOT NULL,
  created_at              timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS compensation_mismatch_detected_at_idx
  ON compensation_mismatch_checkpoints (detected_at DESC);
