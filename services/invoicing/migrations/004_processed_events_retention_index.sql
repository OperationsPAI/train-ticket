-- OutboxRelay cleanup sweeps processed_events by processed_at every 5 minutes,
-- and the table is read on the dedup path of every consumed event, so an
-- unindexed sweep degrades every handler. On the live cluster journey-order's
-- table reached 1,092,388 rows / 205 MB with 1,085,529 rows past retention.
-- See services/journey-order/migrations/003_hot_path_indexes.sql for detail.
CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at ON processed_events(processed_at);
