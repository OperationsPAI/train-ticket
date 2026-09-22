CREATE INDEX IF NOT EXISTS idx_ancillary_pending_refund
ON ancillary_order_items(updated_at)
WHERE data->>'status'='REFUND_PENDING' AND data->>'paymentIntentId' IS NOT NULL;
