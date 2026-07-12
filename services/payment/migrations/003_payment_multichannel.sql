CREATE INDEX IF NOT EXISTS idx_payment_intent_snapshots_expiry_open
  ON payment_intent_snapshots (((data->>'expiresAt')::timestamptz))
  WHERE data->>'status' IN ('CREATED', 'AUTHORIZED');

-- Snapshots are JSONB; REQ-307 fields are stored in payment_intent_snapshots.data:
-- channelRef, refundHistory[], channel-specific expiresAt, and PaymentTimedOut domain events.
