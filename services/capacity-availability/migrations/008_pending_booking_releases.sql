CREATE TABLE IF NOT EXISTS pending_booking_releases (
    segment_booking_ref text PRIMARY KEY,
    envelope jsonb NOT NULL,
    release_reason text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
