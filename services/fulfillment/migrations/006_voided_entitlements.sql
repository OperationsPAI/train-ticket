CREATE TABLE IF NOT EXISTS voided_entitlements (
    entitlement_id text NOT NULL,
    segment_booking_id text NOT NULL,
    event_id text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (entitlement_id,segment_booking_id)
);
