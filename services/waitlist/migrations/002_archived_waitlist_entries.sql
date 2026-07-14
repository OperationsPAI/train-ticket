CREATE TABLE IF NOT EXISTS archived_waitlist_entries (
    entry_id text PRIMARY KEY,
    account_id text NOT NULL,
    traveler_refs jsonb NOT NULL,
    segment_ref text NOT NULL,
    departure_date date NOT NULL,
    seat_class text NOT NULL,
    terminal_status text NOT NULL CHECK (terminal_status IN ('ACCEPTED', 'EXPIRED', 'CANCELLED')),
    archived_at timestamptz NOT NULL,
    data jsonb NOT NULL,
    source_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS archived_waitlist_entries_traveler_idx
    ON archived_waitlist_entries USING gin (traveler_refs jsonb_path_ops);

CREATE INDEX IF NOT EXISTS archived_waitlist_entries_archived_at_idx
    ON archived_waitlist_entries (archived_at DESC, entry_id);
