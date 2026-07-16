-- Reconcile existing deployments that still have the pre-REQ-081A expiry index
-- defined as ((data->>'validUntil')::numeric). validUntil is an RFC3339 UTC
-- timestamp string, so the ::numeric cast makes every insert fail with
-- "invalid input syntax for type numeric". Drop and recreate as the text index
-- (RFC3339 UTC strings sort chronologically; the timestamptz cast is not
-- IMMUTABLE in an index expression).
DROP INDEX IF EXISTS idx_promotion_expiry;
CREATE INDEX IF NOT EXISTS idx_promotion_expiry
  ON promotion_instrument_snapshots ((data->>'validUntil'))
  WHERE data->>'status' IN ('ISSUED', 'RESERVED', 'RELEASED');
