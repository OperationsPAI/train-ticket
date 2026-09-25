-- The outbox retention sweep deletes published rows older than thirty seconds,
-- and the only outbox indexes cover `published_at IS NULL`, so the sweep
-- scanned the table on every pass and reported that it had hit its batch
-- budget.
CREATE INDEX IF NOT EXISTS idx_outbox_published_at
    ON outbox (published_at)
    WHERE published_at IS NOT NULL;
