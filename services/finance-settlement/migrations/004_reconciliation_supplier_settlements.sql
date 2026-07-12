CREATE TABLE IF NOT EXISTS reconciliation_batch_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_reconciliation_batch_snapshots_settlement_date
  ON reconciliation_batch_snapshots ((data->>'settlementDate'));

CREATE TABLE IF NOT EXISTS supplier_settlement_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_supplier_settlement_snapshots_supplier_period
  ON supplier_settlement_snapshots ((data->>'supplierId'), (data->>'startDate'), (data->>'endDate'));

CREATE TABLE IF NOT EXISTS fee_accrual_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_fee_accrual_snapshots_order_id
  ON fee_accrual_snapshots ((data->>'orderId'));

ALTER TABLE captures_by_order_id
  ADD COLUMN IF NOT EXISTS occurred_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_captures_by_order_id_occurred_at
  ON captures_by_order_id (occurred_at);

CREATE TABLE IF NOT EXISTS channel_statement_lines (
  statement_line_id   text PRIMARY KEY,
  channel_statement_id text NOT NULL,
  statement_date      text NOT NULL,
  order_id            text NOT NULL DEFAULT '',
  payment_intent_id   text NOT NULL DEFAULT '',
  channel_order_id    text NOT NULL DEFAULT '',
  currency            text NOT NULL,
  amount              numeric(19, 4) NOT NULL,
  source_event_id     text NOT NULL,
  updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_channel_statement_lines_statement_date
  ON channel_statement_lines (statement_date);
CREATE INDEX IF NOT EXISTS idx_channel_statement_lines_order_payment
  ON channel_statement_lines (order_id, payment_intent_id);
