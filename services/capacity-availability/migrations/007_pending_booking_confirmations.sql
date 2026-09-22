CREATE TABLE IF NOT EXISTS pending_booking_confirmations (
    segment_booking_ref text PRIMARY KEY,
    envelope jsonb NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
