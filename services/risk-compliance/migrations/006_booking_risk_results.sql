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
