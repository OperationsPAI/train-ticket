-- The refunds payment has actually settled, in one small indexed table.
--
-- A settlement date's reconciliation compares the channel's refund lines with
-- the platform's refunds, and the channel line carries the refund id -- but the
-- only place that id and its settled instant exist is inside the refund
-- snapshot's domain events, where a date range cannot be read without walking
-- every snapshot. This table is that reading, written when a refund settles and
-- backfilled here from the events the snapshots already carry.
CREATE TABLE IF NOT EXISTS refund_settlements (
    refund_id TEXT PRIMARY KEY,
    payment_intent_id TEXT NOT NULL DEFAULT '',
    business_reference TEXT NOT NULL DEFAULT '',
    source_case_ref TEXT NOT NULL DEFAULT '',
    currency TEXT NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    settled_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_refund_settlements_settled_at
  ON refund_settlements (settled_at, refund_id);

-- Backfill from the settled snapshots' own RefundSettled event: it names the
-- refund, the intent, the business reference and the instant it settled.
INSERT INTO refund_settlements(refund_id, payment_intent_id, business_reference, source_case_ref, currency, amount_minor_units, settled_at)
SELECT r.data->>'refundId',
       coalesce(r.data->>'paymentIntentId', ''),
       coalesce(e.value->'payload'->>'businessRef', ''),
       coalesce(r.data->>'sourceCaseRef', ''),
       e.value->'payload'->'amount'->>'currency',
       (e.value->'payload'->'amount'->>'minorUnits')::bigint,
       (e.value->>'occurredAt')::timestamptz
FROM refund_snapshots r
CROSS JOIN LATERAL jsonb_array_elements(r.data->'domainEvents') AS e(value)
WHERE r.data->>'status' = 'SETTLED'
  AND e.value->>'eventType' = 'RefundSettled'
  AND coalesce(e.value->'payload'->'amount'->>'minorUnits', '') ~ '^-?[0-9]+$'
ON CONFLICT (refund_id) DO NOTHING;
