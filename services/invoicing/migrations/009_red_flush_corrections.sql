CREATE TABLE IF NOT EXISTS red_flush_correction_audit (
  original_red_flush_id text PRIMARY KEY,
  case_id text NOT NULL,
  original_red_flush jsonb NOT NULL,
  original_red_invoice jsonb NOT NULL,
  refund_evidence jsonb NOT NULL,
  corrected_at timestamptz NOT NULL DEFAULT now()
);
