-- This service creates processed_events and outbox but never created
-- idempotency_records, and the kit's retention sweep covers all three. Every
-- sweep therefore failed on the third statement: the deployed cluster logged
-- 946 occurrences of `relation "idempotency_records" does not exist` in a
-- single log window, one per sweep per uvicorn worker.
--
-- created_at is indexed for the same reason as the other services: the sweep
-- filters on it.
CREATE TABLE IF NOT EXISTS idempotency_records (
    key TEXT PRIMARY KEY,
    request_hash TEXT NOT NULL,
    status_code INT NOT NULL,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_idempotency_records_created_at ON idempotency_records (created_at);
