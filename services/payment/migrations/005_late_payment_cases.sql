-- Late payment cases: captures that arrive after the intent was cancelled or
-- expired. See docs/02-domains/payment.md 5.5 / 6.4.
CREATE TABLE IF NOT EXISTS late_payment_case_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Operators work this table by payment intent ("did this customer's money land
-- after we expired their intent?"), and finance-settlement reconciles by
-- channel transaction. Neither lookup is by primary key.
CREATE INDEX IF NOT EXISTS idx_late_payment_case_snapshots_payment_intent
  ON late_payment_case_snapshots ((data->>'paymentIntentId'));

CREATE INDEX IF NOT EXISTS idx_late_payment_case_snapshots_open
  ON late_payment_case_snapshots ((data->>'channelTransactionId'))
  WHERE data->>'status' = 'OPEN';
