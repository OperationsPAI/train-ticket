-- The capacity service sweeps capacity_supply_observations by observed_at (see
-- src/adapters/storage/retention.rs). The column was unindexed, so the sweep
-- planned a Seq Scan over the whole table, and every row is a sixteen kilobyte
-- supply document, so the scan also detoasted it. On the live cluster the table
-- held 746808 rows across 12 GB, one row per inventory pool, and it grows
-- because every bootstrap creates new segments and therefore new pools that the
-- eviction-conflict upsert never touches again.
--
-- The sweep keeps the row while its pool is re-observed, so the index only has
-- to order the rows that fell out of the window; a partial index would add
-- nothing and cannot be expressed without pinning the window.
CREATE INDEX IF NOT EXISTS idx_capacity_supply_observations_observed_at
  ON capacity_supply_observations (observed_at);
