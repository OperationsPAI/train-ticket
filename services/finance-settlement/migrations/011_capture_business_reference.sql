-- The capture projection is the platform's side of the money: one row per
-- payment it received, and the key is the row's identity rather than the
-- business reference, because a change difference payment is attributed to the
-- post-sales case while a purchase is attributed to the journey order.
ALTER TABLE captures_by_order_id ADD COLUMN IF NOT EXISTS business_reference TEXT;
UPDATE captures_by_order_id SET business_reference = order_id WHERE business_reference IS NULL;

-- Repair the receipts the platform never recorded. Every change payment it
-- recognized has a recognition whose order item IS the payment intent, and the
-- day's reconciliation reported each of those payments as channel money the
-- platform could not explain: measured on 2026-09-26, 56,884 of the day's
-- 488,414 channel lines were exactly these. The rows are keyed by the payment
-- intent, which is unique, and carry the order the money was recognized against.
INSERT INTO captures_by_order_id(order_id, payment_intent_id, currency, amount, source_event_id, occurred_at, business_reference)
SELECT r.data->>'orderItemId',
       r.data->>'orderItemId',
       r.data->'amount'->>'currency',
       (r.data->'amount'->>'amount')::numeric,
       r.data->>'sourceEventId',
       (r.data->>'recognizedAt')::timestamptz,
       r.data->>'orderId'
FROM revenue_recognition_snapshots r
WHERE r.data->>'componentCode' = 'change-payment'
  AND coalesce(r.data->>'orderItemId', '') <> ''
ON CONFLICT (order_id) DO NOTHING;
