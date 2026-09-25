-- Covering indexes for the periodic conservation read.
--
-- The conservation gauge aggregates every captured amount and every settled
-- refund by currency. Without an index that is a sequential scan of a nine
-- gigabyte snapshot table: measured on the resident database, the full query
-- took 59 seconds before these indexes and 33 seconds after, and the read runs
-- on a timer for the life of the process.
--
-- On a database that already holds payment history, create both indexes ONLINE
-- before this migration runs:
--
--   CREATE INDEX CONCURRENTLY payment_intent_captured_amount_idx
--     ON payment_intent_snapshots (((data->'capturedAmount'->>'currency')),
--                                  ((data->'capturedAmount'->>'minorUnits')::numeric))
--     WHERE data->'capturedAmount'->>'currency' IS NOT NULL;
--   CREATE INDEX CONCURRENTLY refund_settled_amount_idx
--     ON refund_snapshots (((data->'amount'->>'currency')),
--                          ((data->'amount'->>'minorUnits')::numeric))
--     WHERE data->>'status' = 'SETTLED';
--
-- The statements below are the IF NOT EXISTS form of the same two indexes, so a
-- database that already has them (or that had them built online) applies this
-- migration as a no-op instead of blocking payment writes behind a transaction
-- that cannot use CONCURRENTLY.
CREATE INDEX IF NOT EXISTS payment_intent_captured_amount_idx
  ON payment_intent_snapshots (
    ((data->'capturedAmount'->>'currency')),
    ((data->'capturedAmount'->>'minorUnits')::numeric)
  )
  WHERE data->'capturedAmount'->>'currency' IS NOT NULL;

CREATE INDEX IF NOT EXISTS refund_settled_amount_idx
  ON refund_snapshots (
    ((data->'amount'->>'currency')),
    ((data->'amount'->>'minorUnits')::numeric)
  )
  WHERE data->>'status' = 'SETTLED';

-- The planner still prefers the parallel sequential scan on the populated
-- table, whose visibility map the snapshot writes keep sparse, so the index is
-- a floor rather than a guarantee. Making the conservation read an index-only
-- scan needs the table vacuumed; that is an operational follow-up recorded in
-- docs/TODO.md, not something a migration can assert.
