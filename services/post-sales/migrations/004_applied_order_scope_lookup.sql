CREATE INDEX IF NOT EXISTS idx_post_sales_applied_order
ON post_sales_case_snapshots ((data->>'journeyOrderId'),id)
WHERE data->>'status'='APPLIED';
