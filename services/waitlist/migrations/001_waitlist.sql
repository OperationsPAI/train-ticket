CREATE TABLE IF NOT EXISTS waitlist_entries (
    entry_id text PRIMARY KEY,
    account_id text NOT NULL,
    traveler_refs jsonb NOT NULL,
    traveler_ref text,
    segment_ref text NOT NULL,
    departure_date date NOT NULL,
    seat_class text NOT NULL,
    priority_score integer NOT NULL CHECK (priority_score >= 0 AND priority_score <= 100),
    status text NOT NULL CHECK (status IN ('DRAFT', 'QUEUED', 'MATCHING', 'FULFILLED', 'EXPIRED', 'CANCELLED', 'SUSPENDED', 'CLOSED')),
    offered_at timestamptz,
    offer_expires_at timestamptz,
    fare_quote_id text,
    capacity_hold_id text,
    offer_version integer,
    journey_order_ref text,
    deadline timestamptz,
    payment_guarantee_ref text,
    itinerary_ref text,
    intent_fingerprint text,
    data jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE waitlist_entries
    ADD COLUMN IF NOT EXISTS traveler_ref text,
    ADD COLUMN IF NOT EXISTS journey_order_ref text,
    ADD COLUMN IF NOT EXISTS deadline timestamptz,
    ADD COLUMN IF NOT EXISTS payment_guarantee_ref text,
    ADD COLUMN IF NOT EXISTS itinerary_ref text,
    ADD COLUMN IF NOT EXISTS intent_fingerprint text;

ALTER TABLE waitlist_entries DROP CONSTRAINT IF EXISTS waitlist_entries_status_check;
ALTER TABLE waitlist_entries ADD CONSTRAINT waitlist_entries_status_check
    CHECK (status IN ('DRAFT', 'QUEUED', 'MATCHING', 'FULFILLED', 'EXPIRED', 'CANCELLED', 'SUSPENDED', 'CLOSED'));

CREATE INDEX IF NOT EXISTS waitlist_queue_order_idx
    ON waitlist_entries (segment_ref, departure_date, seat_class, priority_score DESC, created_at ASC, entry_id)
    WHERE status = 'QUEUED';

CREATE INDEX IF NOT EXISTS waitlist_deadline_idx
    ON waitlist_entries (deadline)
    WHERE status IN ('DRAFT', 'QUEUED', 'MATCHING', 'SUSPENDED');

CREATE UNIQUE INDEX IF NOT EXISTS waitlist_active_intent_unique_idx
    ON waitlist_entries (traveler_ref, intent_fingerprint)
    WHERE status IN ('DRAFT', 'QUEUED', 'MATCHING', 'SUSPENDED');

CREATE TABLE IF NOT EXISTS waitlist_offers (
    offer_id text PRIMARY KEY,
    entry_id text NOT NULL REFERENCES waitlist_entries(entry_id),
    fare_quote_id text NOT NULL,
    capacity_hold_id text NOT NULL,
    offer_version integer NOT NULL DEFAULT 1,
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
    stream text,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, stream)
);
