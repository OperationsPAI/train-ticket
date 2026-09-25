CREATE TABLE IF NOT EXISTS capacity_hold_fences (
    capacity_hold_id text PRIMARY KEY,
    terminal_state text NOT NULL CHECK (terminal_state IN ('RELEASED', 'EXPIRED')),
    source_event_id text NOT NULL,
    terminal_at timestamptz NOT NULL,
    capacity_unit_ref text NOT NULL,
    from_seq integer NOT NULL,
    to_seq integer NOT NULL,
    CHECK (from_seq >= 0 AND to_seq > from_seq)
);
