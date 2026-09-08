-- Index the traveler-containment lookup that three identity handlers depend on.
--
-- OrderManagementService.updateOrdersForTraveler calls findOrdersByTraveler,
-- which filters with `data->'travelers' @> ?::jsonb`. Without a GIN index that
-- is a sequential scan of every order snapshot, and it is on the hot path of
-- CredentialRegistered, VerificationCaseStarted and VerificationPassed --
-- three of journey-order's highest-volume event types.
--
-- Measured on a cluster with 58,156 order snapshots: those three handlers ran
-- at a p50 of ~2,000 ms each and accounted for 838 seconds of handler time in a
-- 1,500-line log sample, while every other handler sat at 50-70 ms.
-- pg_stat_user_tables showed 243,202 sequential scans on the table having read
-- 2.03 billion rows. journey-order's pending backlog grew continuously at about
-- +22/s and reached 72,281.
--
-- After this index the same query plans as a Bitmap Index Scan: cost 14,196 ->
-- 40.8, the three handlers dropped to a p50 of 45 ms, and the
-- events:identity-verification backlog began shrinking for the first time.
--
-- jsonb_path_ops rather than the default jsonb_ops: it indexes only paths that
-- appear in containment queries, which is exactly what `@>` needs here, and
-- produces a smaller index than jsonb_ops' key-and-value entries. The tradeoff
-- is that jsonb_path_ops cannot serve existence operators (`?`, `?|`, `?&`);
-- nothing queries this column that way.
CREATE INDEX IF NOT EXISTS idx_jos_travelers_gin
    ON journey_order_snapshots USING gin ((data->'travelers') jsonb_path_ops);

-- Retention sweeps delete by timestamp, and processed_events is on the
-- deduplication path of every consumed event, so it is the table most damaged by
-- an unindexed sweep.
--
-- OutboxRelay.cleanup() previously issued one unbounded DELETE inside a silent
-- catch. On this cluster that combination left processed_events at 1,092,388
-- rows / 205 MB, with 1,085,529 rows past their five-minute retention and the
-- oldest 21 hours old: the statement planned a Seq Scan, tried to delete a
-- million rows in one transaction, failed, and reported nothing. The relay now
-- deletes in batches and logs failures, but the sweep still wants an index to
-- find the expired rows.
CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at
    ON processed_events (processed_at);
