-- text::timestamp / ::timestamptz casts are only STABLE (they depend on the
-- DateStyle / TimeZone GUCs), so Postgres refuses them in an index expression
-- (ERROR: functions in index expression must be marked IMMUTABLE). expiresAt is
-- always an RFC3339 UTC Instant string (T...Z), which parses deterministically
-- regardless of those GUCs, so an IMMUTABLE wrapper is safe here.
CREATE OR REPLACE FUNCTION payment_expires_at_immutable(snapshot_data jsonb)
  RETURNS timestamp
  LANGUAGE sql
  IMMUTABLE
  PARALLEL SAFE
AS $$ SELECT (snapshot_data->>'expiresAt')::timestamp $$;

CREATE INDEX IF NOT EXISTS idx_payment_intent_snapshots_expiry_open
  ON payment_intent_snapshots (payment_expires_at_immutable(data))
  WHERE data->>'status' IN ('CREATED', 'AUTHORIZED');

-- Snapshots are JSONB; REQ-307 fields are stored in payment_intent_snapshots.data:
-- channelRef, refundHistory[], channel-specific expiresAt, and PaymentTimedOut domain events.
