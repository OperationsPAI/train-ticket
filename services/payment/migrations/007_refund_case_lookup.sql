CREATE INDEX IF NOT EXISTS idx_refund_snapshots_business_case
  ON refund_snapshots ((data->>'sourceCaseRef'),id);
