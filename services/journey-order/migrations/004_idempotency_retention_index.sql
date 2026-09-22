-- The outbox relay sweeps idempotency_records by created_at every 20 polls,
-- and the column was unindexed, so the sweep planned a Seq Scan over the whole
-- table. On the live cluster this database held 4756058 rows in 6028 MB,
-- 4748946 of them past the ten minute retention.
--
-- See services/journey-order/migrations/003_hot_path_indexes.sql for the same
-- problem on processed_events.
CREATE INDEX IF NOT EXISTS idx_idempotency_records_created_at ON idempotency_records (created_at);
