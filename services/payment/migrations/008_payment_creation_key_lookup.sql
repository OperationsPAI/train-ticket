CREATE INDEX IF NOT EXISTS idx_payment_intent_creation_key
  ON payment_intent_snapshots ((data->>'idempotencyKey'));
