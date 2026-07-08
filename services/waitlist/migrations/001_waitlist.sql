CREATE TABLE IF NOT EXISTS waitlist_requests (
    waitlist_request_id text PRIMARY KEY,
    traveler_ref text NOT NULL,
    intent_fingerprint text NOT NULL,
    segment_ref text NOT NULL,
    status text NOT NULL,
    deadline timestamptz NOT NULL,
    queued_at timestamptz,
    order_ref text,
    version bigint NOT NULL,
    data jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS active_waitlist_request
    ON waitlist_requests (traveler_ref, intent_fingerprint)
    WHERE status IN ('DRAFT', 'QUEUED', 'MATCHING', 'SUSPENDED');

CREATE INDEX IF NOT EXISTS waitlist_queue_head_idx
    ON waitlist_requests (segment_ref, queued_at, created_at, waitlist_request_id)
    WHERE status = 'QUEUED';
CREATE INDEX IF NOT EXISTS waitlist_order_ref_idx ON waitlist_requests (order_ref) WHERE order_ref IS NOT NULL;

CREATE TABLE IF NOT EXISTS idempotency_records (key text PRIMARY KEY, request_hash text NOT NULL, status_code int NOT NULL, response_body jsonb, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS outbox (seq bigserial PRIMARY KEY, event_id text NOT NULL UNIQUE, stream text NOT NULL, envelope jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz);
CREATE TABLE IF NOT EXISTS processed_events (event_id text NOT NULL, stream text NOT NULL, processed_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (event_id, stream));
