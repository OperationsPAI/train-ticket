-- The outbox relay sweeps idempotency_records by created_at every 20 polls,
-- and the column was unindexed in every service, so the sweep planned a Seq
-- Scan over the whole table. The table is also read by key on every idempotent
-- request, so an unbounded table costs the hot path as well as the sweep.
CREATE INDEX IF NOT EXISTS idx_idempotency_records_created_at ON idempotency_records (created_at);
