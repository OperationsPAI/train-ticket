-- Index the idempotency lookup that every hold request performs before it acts.
--
-- find_hold_by_idempotency_key (adapters/storage/mod.rs:955) filters on
-- data->>'idempotencyKey' and data->>'state'. idx_capacity_hold_segment_booking_state
-- leads with segmentBookingRef, so it cannot serve this predicate, and the
-- query plans a Seq Scan of every hold.
--
-- Measured on the live cluster with 377,131 rows / 395 MB: the plan read 42,461
-- buffers to return one row or none, and pg_stat_user_tables reported 912,749
-- sequential scans having read 81.5 billion tuples, growing at 119 million
-- tuples per minute. The lookup is on the path of every request_hold, so its
-- cost is paid once per hold operation.
--
-- idempotencyKey alone is nearly unique, so state follows only to let the
-- planner apply both conditions from the index. updated_at is not indexed: with
-- at most a handful of rows per key, the ORDER BY sorts what the index scan
-- already returned.
CREATE INDEX IF NOT EXISTS idx_capacity_hold_idempotency_state
  ON capacity_hold_snapshots ((data ->> 'idempotencyKey'), (data ->> 'state'));
