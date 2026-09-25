CREATE TABLE IF NOT EXISTS waitlist_capacity_credits (
    capacity_release_ref text PRIMARY KEY,
    segment_ref text NOT NULL,
    scheduled_service_ref text NOT NULL,
    departure_date date NOT NULL,
    seat_class text NOT NULL,
    from_seq integer NOT NULL CHECK (from_seq >= 0),
    to_seq integer NOT NULL CHECK (to_seq > from_seq),
    stop_sequence_version text NOT NULL,
    intent_fingerprint text,
    released_slots integer NOT NULL CHECK (released_slots > 0),
    remaining_slots integer NOT NULL CHECK (remaining_slots >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS waitlist_available_capacity_credit_idx
    ON waitlist_capacity_credits (
        scheduled_service_ref,
        departure_date,
        seat_class,
        stop_sequence_version,
        from_seq,
        to_seq,
        created_at,
        capacity_release_ref
    )
    WHERE remaining_slots > 0;
