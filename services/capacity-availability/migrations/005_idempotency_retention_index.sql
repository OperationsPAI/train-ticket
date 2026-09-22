-- The outbox relay sweeps idempotency_records by created_at, and the column
-- was unindexed, so the sweep planned a Seq Scan over the whole table. On the
-- live cluster this database held 857929 rows in 613 MB, 853203 of them past
-- the ten minute retention.
CREATE INDEX IF NOT EXISTS idx_idempotency_records_created_at ON idempotency_records (created_at);
