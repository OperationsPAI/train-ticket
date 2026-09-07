CREATE TABLE IF NOT EXISTS group_bookings (
    group_booking_id TEXT PRIMARY KEY,
    organizer_ref TEXT NOT NULL,
    segment_refs JSONB NOT NULL,
    target_traveler_count INTEGER NOT NULL CHECK (target_traveler_count >= 10),
    status TEXT NOT NULL,
    fare_currency CHAR(3) NOT NULL,
    fare_minor_units BIGINT NOT NULL CHECK (fare_minor_units >= 0),
    discount_basis_points INTEGER NOT NULL CHECK (discount_basis_points BETWEEN 0 AND 10000),
    negotiation_ref TEXT,
    capacity_hold_id TEXT,
    cancellation_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS group_members (
    member_id TEXT PRIMARY KEY,
    group_booking_id TEXT NOT NULL REFERENCES group_bookings(group_booking_id) ON DELETE CASCADE,
    traveler_ref TEXT NOT NULL,
    masked_document_ref TEXT NOT NULL,
    status TEXT NOT NULL,
    added_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_group_members_booking ON group_members(group_booking_id);

-- The roster uniqueness rule is ACTIVE-scoped, not absolute. GroupBooking's
-- activeTravelerExists() only rejects a traveler_ref that is currently ACTIVE,
-- so a traveler whose earlier membership was CANCELLED may legally be re-added
-- (GroupMember.cancel() keeps the cancelled row for audit rather than deleting
-- it). A plain UNIQUE (group_booking_id, traveler_ref) would reject that legal
-- re-add, so the constraint must be a partial index over ACTIVE rows only.
CREATE UNIQUE INDEX IF NOT EXISTS idx_group_members_active_traveler
    ON group_members(group_booking_id, traveler_ref)
    WHERE status = 'ACTIVE';

-- Platform tables. All nine sibling Java services create these in their 001
-- migration, and the platform-kit primitives address them by these exact
-- names: OutboxAppender/OutboxRelay require outbox(seq, event_id, stream,
-- envelope, published_at), ProcessedEventStore requires processed_events, and
-- DbIdempotencyStore requires idempotency_records. The table this migration
-- previously declared -- group_booking_outbox(outbox_id, event_type,
-- aggregate_id, payload, occurred_at) -- matched no reader or writer anywhere
-- in the repository and could not be driven by OutboxRelay.
CREATE TABLE IF NOT EXISTS outbox (
    seq bigserial PRIMARY KEY,
    event_id text NOT NULL UNIQUE,
    stream text NOT NULL,
    envelope jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished_seq ON outbox (seq) WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS processed_events (
    event_id text PRIMARY KEY,
    stream text,
    processed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS idempotency_records (
    key text PRIMARY KEY,
    request_hash text NOT NULL,
    status_code int NOT NULL,
    response_body jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
