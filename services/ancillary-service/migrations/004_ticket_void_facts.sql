CREATE TABLE IF NOT EXISTS ancillary_voided_tickets (
    entitlement_id text PRIMARY KEY,
    event_id text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ancillary_items_entitlement ON ancillary_order_items ((data->>'entitlementRef'));
