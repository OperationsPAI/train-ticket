CREATE TABLE IF NOT EXISTS benefit_cost_entries (
  event_id        text PRIMARY KEY,
  benefit_id      text NOT NULL,
  account_id      text NOT NULL,
  issuance_source text NOT NULL,
  case_id         text,
  currency        text NOT NULL,
  amount          numeric(19, 4) NOT NULL,
  event_type      text NOT NULL,
  occurred_at     timestamptz NOT NULL,
  updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_benefit_cost_entries_account_occurred
  ON benefit_cost_entries (account_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_benefit_cost_entries_benefit_occurred
  ON benefit_cost_entries (benefit_id, occurred_at DESC);
