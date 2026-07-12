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
