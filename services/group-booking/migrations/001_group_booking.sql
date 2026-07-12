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
    added_at TIMESTAMPTZ NOT NULL,
    UNIQUE (group_booking_id, traveler_ref)
);

CREATE TABLE IF NOT EXISTS group_booking_outbox (
    outbox_id BIGSERIAL PRIMARY KEY,
    event_id TEXT NOT NULL UNIQUE,
    event_type TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_group_members_booking ON group_members(group_booking_id);
CREATE INDEX IF NOT EXISTS idx_group_booking_outbox_unpublished ON group_booking_outbox(published_at) WHERE published_at IS NULL;
