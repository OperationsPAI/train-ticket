CREATE TABLE IF NOT EXISTS order_risk_results (
    order_id text PRIMARY KEY,
    account_id text NOT NULL,
    assessment_id text NOT NULL REFERENCES risk_assessment_snapshots(id)
);
CREATE TABLE IF NOT EXISTS booking_risk_requests (
    saga_id text PRIMARY KEY,
    order_id text NOT NULL,
    account_id text NOT NULL,
    envelope jsonb NOT NULL,
    completed_at timestamptz,
    received_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_booking_risk_requests_pending
    ON booking_risk_requests(order_id) WHERE completed_at IS NULL;

INSERT INTO order_risk_results(order_id,account_id,assessment_id)
SELECT DISTINCT ON (a.order_id) a.order_id,a.account_id,s.id
FROM risk_order_accounts a
JOIN risk_assessment_snapshots s ON s.data->>'subjectRef'=a.order_id AND s.data->>'scenario'='order_risk'
ORDER BY a.order_id,s.updated_at,s.id
ON CONFLICT (order_id) DO NOTHING;
