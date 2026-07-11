CREATE TABLE IF NOT EXISTS waitlist_entries (
    entry_id text PRIMARY KEY,
    account_id text NOT NULL,
    traveler_refs jsonb NOT NULL,
    segment_ref text NOT NULL,
    departure_date date NOT NULL,
    seat_class text NOT NULL,
    priority_score integer NOT NULL CHECK (priority_score >= 0 AND priority_score <= 100),
    status text NOT NULL CHECK (status IN ('QUEUED', 'OFFERED', 'ACCEPTED', 'EXPIRED', 'CANCELLED')),
    offered_at timestamptz,
    offer_expires_at timestamptz,
    fare_quote_id text,
    capacity_hold_id text,
    data jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS waitlist_queue_order_idx
    ON waitlist_entries (segment_ref, departure_date, seat_class, priority_score DESC, created_at ASC, entry_id)
    WHERE status = 'QUEUED';

CREATE INDEX IF NOT EXISTS waitlist_offer_expiry_idx
    ON waitlist_entries (offer_expires_at)
    WHERE status = 'OFFERED';

CREATE TABLE IF NOT EXISTS waitlist_offers (
    offer_id text PRIMARY KEY,
    entry_id text NOT NULL REFERENCES waitlist_entries(entry_id),
    fare_quote_id text NOT NULL,
    capacity_hold_id text NOT NULL,
    expires_at timestamptz NOT NULL,
    accepted_at timestamptz,
    expired_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS idempotency_records (
    key text PRIMARY KEY,
    request_hash text NOT NULL,
    status_code int NOT NULL,
    response_body jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS outbox (
    seq bigserial PRIMARY KEY,
    event_id text NOT NULL UNIQUE,
    stream text NOT NULL,
    envelope jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE TABLE IF NOT EXISTS processed_events (
    event_id text NOT NULL,
    stream text NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, stream)
);
