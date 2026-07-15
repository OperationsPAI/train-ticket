CREATE INDEX IF NOT EXISTS idx_payment_intent_snapshots_expiry_open
  ON payment_intent_snapshots (((data->>'expiresAt')::timestamp))
  WHERE data->>'status' IN ('CREATED', 'AUTHORIZED');

-- Snapshots are JSONB; REQ-307 fields are stored in payment_intent_snapshots.data:
-- channelRef, refundHistory[], channel-specific expiresAt, and PaymentTimedOut domain events.
-- expiresAt is an RFC3339 UTC (Z) Instant string, so the timestamp cast (without
-- time zone) is IMMUTABLE and valid in an index expression; a timestamptz cast is
-- only STABLE and Postgres rejects it. findExpiredOpenIntents casts identically.
